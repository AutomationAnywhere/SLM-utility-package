package com.automationanywhere.botcommand.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Manages a llama-server subprocess for local model inference.
 *
 * Replaces the de.kherud:llama JNI binding which was pinned to llama.cpp b4916
 * and did not support qwen3, gemma4, or other architectures added after March 2025.
 *
 * The official llama.cpp server binary supports all models and both Windows/Mac.
 * Model loading is the same speed (5-30s first time), but subsequent calls are
 * fast HTTP requests to the in-process server.
 */
public class LlamaServerManager {
    private static final Logger logger = LogManager.getLogger(LlamaServerManager.class);
    private static volatile LlamaServerManager instance;

    private Process serverProcess;
    private Thread stderrDrainThread;
    private int port = -1;
    private String currentModelId;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final Gson gson = new Gson();

    private LlamaServerManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "llama-server-shutdown"));
    }

    public static LlamaServerManager getInstance() {
        if (instance == null) {
            synchronized (LlamaServerManager.class) {
                if (instance == null) {
                    instance = new LlamaServerManager();
                }
            }
        }
        return instance;
    }

    /**
     * Ensure llama-server is running with the specified model.
     * If the server is already running with this model, does nothing (fast).
     * If a different model is loaded, the server is restarted.
     */
    public synchronized void ensureModelLoaded(ModelManager.ModelType modelType) throws Exception {
        // Reuse existing server if the model matches
        if (isRunning() && modelType.getId().equals(currentModelId)) {
            logger.debug("Reusing running server for model: {}", currentModelId);
            return;
        }

        // Stop any existing server
        stopInternal();

        Path modelPath = ModelManager.getInstance().getModelPath(modelType);
        if (!Files.exists(modelPath)) {
            throw new RuntimeException(
                "Model file not found: " + modelPath + ". Run Validate Device first.");
        }

        // Ensure llama-server binary is installed
        LlamaBinaryManager.ensureInstalled();

        port = findFreePort();
        currentModelId = modelType.getId();
        startServer(modelPath, modelType);
    }

    private void startServer(Path modelPath, ModelManager.ModelType modelType) throws Exception {
        Path serverBin = LlamaBinaryManager.getLlamaServerPath();
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("windows");

        // Build the path string — forward slashes on Windows to avoid any parsing edge cases
        String absModelPath = modelPath.toAbsolutePath().toString();
        if (isWindows) absModelPath = absModelPath.replace('\\', '/');

        // Cap context at 8192 (safe on any 8GB+ machine)
        int ctx = Math.min(modelType.getContextWindow(), 8192);

        List<String> cmd = new ArrayList<>(Arrays.asList(
            serverBin.toString(),
            "-m",     absModelPath,
            "--port", String.valueOf(port),
            "-ngl",   "0",          // CPU only — no GPU layers
            "-c",     String.valueOf(ctx),
            "-np",    "1"           // 1 parallel slot is enough for bot tasks
        ));

        // Disable memory-mapped file I/O on Windows (avoids MapViewOfFile issues
        // in restricted security contexts like the AA Bot Agent)
        if (isWindows) cmd.add("--no-mmap");

        logger.info("Starting llama-server: model={}, port={}, ctx={}", modelType.getId(), port, ctx);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Working directory = bin dir so Windows can find the side-by-side DLLs
        pb.directory(serverBin.getParent().toFile());

        // Redirect server stdout+stderr to a log file (prevents buffer fill blocking)
        Path logFile = ModelManager.getModelCacheDir().resolve("llama-server.log");
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));

        serverProcess = pb.start();
        logger.info("llama-server started. Log: {}", logFile);

        waitForReady(120_000); // Up to 2 min for large models to load
    }

    private void waitForReady(long timeoutMs) throws Exception {
        String healthUrl = "http://127.0.0.1:" + port + "/health";
        long deadline = System.currentTimeMillis() + timeoutMs;

        logger.info("Waiting for llama-server to be ready (up to {}s)...", timeoutMs / 1000);

        while (System.currentTimeMillis() < deadline) {
            if (!serverProcess.isAlive()) {
                Path log = ModelManager.getModelCacheDir().resolve("llama-server.log");
                String tail = readLogTail(log, 20);
                throw new RuntimeException(
                    "llama-server exited unexpectedly. Last log lines:\n" + tail);
            }

            try {
                Request req = new Request.Builder().url(healthUrl).get().build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.code() == 200) {
                        logger.info("llama-server ready on port {} (model: {})", port, currentModelId);
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Not ready yet
            }

            Thread.sleep(500);
        }

        stopInternal();
        throw new RuntimeException("llama-server did not become ready within " + (timeoutMs / 1000) + "s");
    }

    /**
     * Run a completion request against the loaded model.
     *
     * @param prompt        Full prompt text (already formatted with chat template if needed)
     * @param maxTokens     Maximum new tokens to generate
     * @param temperature   Sampling temperature (0 = deterministic)
     * @param stopSequences Token sequences to stop generation at
     * @param timeoutSecs   Request-level timeout in seconds
     * @return Generated text (content only, no prompt echo)
     */
    public String complete(String prompt, int maxTokens, float temperature,
                           String[] stopSequences, int timeoutSecs) throws Exception {
        if (!isRunning()) {
            throw new RuntimeException("llama-server is not running. Call ensureModelLoaded() first.");
        }

        JsonObject body = new JsonObject();
        body.addProperty("prompt", prompt);
        body.addProperty("n_predict", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("stream", false);
        body.addProperty("cache_prompt", true);

        if (stopSequences != null && stopSequences.length > 0) {
            JsonArray stops = new JsonArray();
            for (String s : stopSequences) stops.add(s);
            body.add("stop", stops);
        }

        OkHttpClient timedClient = httpClient.newBuilder()
            .callTimeout(Duration.ofSeconds(timeoutSecs + 5))
            .readTimeout(Duration.ofSeconds(timeoutSecs + 5))
            .build();

        RequestBody reqBody = RequestBody.create(
            gson.toJson(body), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + port + "/completion")
            .post(reqBody)
            .build();

        try (Response response = timedClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("llama-server /completion error HTTP " + response.code()
                    + ": " + response.body().string());
            }
            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
            return json.get("content").getAsString();
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        if (serverProcess != null) {
            if (serverProcess.isAlive()) {
                logger.info("Stopping llama-server (model: {})", currentModelId);
                serverProcess.destroyForcibly();
                try { serverProcess.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            serverProcess = null;
        }
        currentModelId = null;
        port = -1;
    }

    public boolean isRunning() {
        return serverProcess != null && serverProcess.isAlive();
    }

    public void shutdown() {
        stop();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String readLogTail(Path logFile, int lines) {
        if (!Files.exists(logFile)) return "(log file not found)";
        try {
            List<String> all = Files.readAllLines(logFile);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")";
        }
    }
}
