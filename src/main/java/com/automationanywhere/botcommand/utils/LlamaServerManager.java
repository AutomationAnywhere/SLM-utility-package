package com.automationanywhere.botcommand.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * Security: each server instance is started with a randomly-generated UUID
 * API key (--api-key flag). All HTTP requests include the key as an
 * "Authorization: Bearer" header so other local processes cannot piggyback
 * on the inference endpoint.
 *
 * Thread safety: mutable state (port, apiKey, serverProcess, currentModelId)
 * is declared volatile.  complete() captures port and apiKey under a brief
 * synchronized block, then releases the lock before the long HTTP call so
 * that stop()/ensureModelLoaded() are not blocked for the full inference
 * duration.  ensureModelLoaded() and stop() are fully synchronized.
 */
public class LlamaServerManager {
    private static final Logger logger = LogManager.getLogger(LlamaServerManager.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static volatile LlamaServerManager instance;

    // Volatile: writes happen inside synchronized methods; reads in complete()
    // and isRunning() happen outside, so volatile is needed for visibility.
    private volatile Process serverProcess;
    private volatile int     port = -1;
    private volatile String  currentModelId;
    private volatile String  apiKey; // random UUID, regenerated on each server start

    // Generous base timeouts for health-check polling during model load.
    // Per-call inference timeouts are applied in complete() via newBuilder().
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofMinutes(5))
        .callTimeout(Duration.ofMinutes(5))
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

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Ensure llama-server is running with the given model.
     * Fast-path: reuses a healthy server when the model already matches.
     * Slow-path: stops the current server and starts a new one.
     */
    public synchronized void ensureModelLoaded(ModelManager.ModelType modelType) throws Exception {
        if (isRunning() && modelType.getId().equals(currentModelId)) {
            logger.debug("Reusing running server for model: {}", currentModelId);
            return;
        }

        stopInternal();

        Path modelPath = ModelManager.getInstance().getModelPath(modelType);
        if (!Files.exists(modelPath)) {
            throw new RuntimeException(
                "Model file not found: " + modelPath + ". Run Validate Device first.");
        }

        LlamaBinaryManager.ensureInstalled();
        port = findFreePort();

        // startServer() may throw (e.g. model-load timeout). If it does,
        // waitForReady() internally calls stopInternal(), resetting port/apiKey/
        // serverProcess before re-throwing — leaving no stale state.
        startServer(modelPath, modelType);

        // Mark the model as loaded only after the server is confirmed healthy.
        currentModelId = modelType.getId();
    }

    private void startServer(Path modelPath, ModelManager.ModelType modelType) throws Exception {
        Path serverBin = LlamaBinaryManager.getLlamaServerPath();
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("windows");

        String absModelPath = modelPath.toAbsolutePath().toString();
        if (isWindows) absModelPath = absModelPath.replace('\\', '/');

        int ctx = Math.min(modelType.getContextWindow(), 8192);

        // Generate a fresh random API key for this server instance.
        this.apiKey = UUID.randomUUID().toString();

        List<String> cmd = new ArrayList<>(Arrays.asList(
            serverBin.toString(),
            "-m",        absModelPath,
            "--port",    String.valueOf(port),
            "-ngl",      "0",
            "-c",        String.valueOf(ctx),
            "-np",       "1",
            "--api-key", this.apiKey   // reject requests that lack this key
        ));

        if (isWindows) cmd.add("--no-mmap");

        logger.info("Starting llama-server: model={}, port={}, ctx={}", modelType.getId(), port, ctx);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(serverBin.getParent().toFile()); // cwd=binDir for DLL resolution on Windows

        // ProcessBuilder.Redirect.to() truncates the log on each new server start,
        // keeping the file bounded to the output of a single session.
        Path logFile = ModelManager.getModelCacheDir().resolve("llama-server.log");
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));

        serverProcess = pb.start();
        logger.info("llama-server started (PID {}). Log: {}", serverProcess.pid(), logFile);

        waitForReady(120_000);
    }

    private void waitForReady(long timeoutMs) throws Exception {
        String healthUrl = "http://127.0.0.1:" + port + "/health";
        long deadline = System.currentTimeMillis() + timeoutMs;

        logger.info("Waiting for llama-server on port {} (up to {}s)...", port, timeoutMs / 1000);

        while (System.currentTimeMillis() < deadline) {
            // Snapshot the volatile field once per iteration to avoid a race
            // between two reads of the same field within one loop body.
            Process proc = serverProcess;
            if (proc == null || !proc.isAlive()) {
                Path log = ModelManager.getModelCacheDir().resolve("llama-server.log");
                String tail = readLogTail(log, 20);
                stopInternal();
                throw new RuntimeException(
                    "llama-server exited unexpectedly. Last log lines:\n" + tail);
            }

            try {
                Request req = new Request.Builder()
                    .url(healthUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.code() == 200) {
                        logger.info("llama-server ready on port {}", port);
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Not accepting connections yet — keep polling
            }

            Thread.sleep(500);
        }

        stopInternal();
        throw new RuntimeException(
            "llama-server did not become ready within " + (timeoutMs / 1000) + "s");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inference
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run a completion against the loaded model.
     *
     * Port and apiKey are captured under a brief synchronized block, then the
     * lock is released before the HTTP call.  This avoids blocking
     * stop()/ensureModelLoaded() for the full 30-120 s inference window while
     * still preventing a race where stopInternal() zeroes {@code port} to -1
     * between the isRunning() guard and the URL construction below.
     */
    public String complete(String prompt, int maxTokens, float temperature,
                           String[] stopSequences, int timeoutSecs) throws Exception {
        final int    localPort;
        final String localApiKey;
        synchronized (this) {
            if (!isRunning()) {
                throw new RuntimeException(
                    "llama-server is not running. Call ensureModelLoaded() first.");
            }
            localPort   = this.port;
            localApiKey = this.apiKey;
        }

        JsonObject body = new JsonObject();
        body.addProperty("prompt",       prompt);
        body.addProperty("n_predict",    maxTokens);
        body.addProperty("temperature",  temperature);
        body.addProperty("stream",       false);
        body.addProperty("cache_prompt", true);

        if (stopSequences != null && stopSequences.length > 0) {
            JsonArray stops = new JsonArray();
            for (String s : stopSequences) stops.add(s);
            body.add("stop", stops);
        }

        // newBuilder() on an existing OkHttpClient shares the connection pool and
        // dispatcher — only timeout settings differ; no new thread pools are created.
        OkHttpClient timedClient = httpClient.newBuilder()
            .callTimeout(Duration.ofSeconds(timeoutSecs + 5))
            .readTimeout(Duration.ofSeconds(timeoutSecs + 5))
            .build();

        RequestBody reqBody = RequestBody.create(gson.toJson(body), JSON_TYPE);
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + localPort + "/completion")
            .header("Authorization", "Bearer " + localApiKey)
            .post(reqBody)
            .build();

        try (Response response = timedClient.newCall(request).execute()) {
            // Read the body exactly once — OkHttp ResponseBody is single-use and
            // may be null (e.g. for responses with no body).
            ResponseBody responseBody = response.body();
            String bodyStr = responseBody != null ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                throw new RuntimeException(
                    "llama-server /completion HTTP " + response.code()
                    + ": " + (bodyStr.isEmpty() ? "(no body)" : bodyStr));
            }

            JsonObject json = gson.fromJson(bodyStr, JsonObject.class);
            JsonElement contentEl = json != null ? json.get("content") : null;
            if (contentEl == null || contentEl.isJsonNull()) {
                throw new RuntimeException(
                    "llama-server response missing 'content' field. Full response: " + bodyStr);
            }
            return contentEl.getAsString();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stop / cleanup
    // ──────────────────────────────────────────────────────────────────────────

    public synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        if (serverProcess != null) {
            if (serverProcess.isAlive()) {
                logger.info("Stopping llama-server (model: {})", currentModelId);
                serverProcess.destroyForcibly();
                try { serverProcess.waitFor(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            serverProcess = null;
        }
        currentModelId = null;
        apiKey         = null;
        port           = -1;
    }

    public boolean isRunning() {
        Process proc = serverProcess; // single volatile read to avoid double-check races
        return proc != null && proc.isAlive();
    }

    public void shutdown() {
        stop();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Finds a free local port by briefly binding to port 0.
     * setReuseAddress(true) helps the OS reclaim the port faster on some
     * platforms after the ServerSocket is closed.
     */
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
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
