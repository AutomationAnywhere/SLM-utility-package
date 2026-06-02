package com.automationanywhere.botcommand.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Downloads quantized GGUF models from HuggingFace on first use.
 * Implements cross-platform file handling and progress tracking.
 *
 * GGUF (GPT-Generated Unified Format) is optimized for llama.cpp:
 * - Single file contains model + metadata
 * - Quantized for smaller size and faster inference
 * - Optimized for CPU execution
 */
public class ModelDownloader {
    private static final Logger logger = LogManager.getLogger(ModelDownloader.class);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)  // 10 min for large files
        .build();

    /**
     * Ensure the llama-server binary is installed.
     * Delegates to LlamaBinaryManager which handles platform detection and download.
     */
    public static void ensureLlamaBinary() throws Exception {
        LlamaBinaryManager.ensureInstalled();
    }

    /**
     * Download a model if it doesn't exist locally
     * @param modelType The model type to download
     * @throws Exception if download fails
     */
    public static void downloadModel(ModelManager.ModelType modelType) throws Exception {
        ModelManager manager = ModelManager.getInstance();
        Path modelDir = manager.getModelDirectory(modelType);
        Path modelPath = manager.getModelPath(modelType);

        // Create model directory if it doesn't exist
        if (!Files.exists(modelDir)) {
            Files.createDirectories(modelDir);
            logger.info("Created model directory: {}", modelDir);
        }

        String url = modelType.getDownloadUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("No download URL configured for model: " + modelType);
        }

        // Download model file if not present
        if (!Files.exists(modelPath)) {
            logger.info("Downloading model {} (~{}MB) to {}",
                modelType.getId(), modelType.getSizeMB(), modelPath);
            downloadFile(url, modelPath);
            logger.info("Model download completed: {}", modelPath);
        } else {
            logger.debug("Model already exists: {}", modelPath);
        }
    }

    /**
     * Download a file from URL to local path (cross-platform)
     * @param url The URL to download from
     * @param destination The local file path to save to
     * @throws Exception if download fails
     */
    private static void downloadFile(String url, Path destination) throws Exception {
        Request request = new Request.Builder()
            .url(url)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: HTTP " + response.code() + " for URL: " + url);
            }

            if (response.body() == null) {
                throw new IOException("Empty response body from: " + url);
            }

            long totalBytes = response.body().contentLength();
            logger.info("Downloading {} from {}",
                totalBytes > 0 ? formatBytes(totalBytes) : "unknown size", url);

            // Download to a temp file first, then atomically rename to the final destination.
            // The temp file is in the same directory as the destination so that Files.move()
            // is an atomic rename on most filesystems (avoids a partial file being visible).
            Path tempFile = Files.createTempFile(destination.getParent(), "download-", ".tmp");
            try {
                try (InputStream in = response.body().byteStream();
                     OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {

                    byte[] buffer = new byte[8192];
                    long downloadedBytes = 0;
                    int bytesRead;
                    long lastLogTime = System.currentTimeMillis();
                    int lastPercent = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        // Log progress every 5 seconds or every 10%
                        long currentTime = System.currentTimeMillis();
                        int currentPercent = totalBytes > 0 ? (int)((downloadedBytes * 100) / totalBytes) : 0;

                        if (currentTime - lastLogTime > 5000 || currentPercent >= lastPercent + 10) {
                            if (totalBytes > 0) {
                                logger.info("Download progress: {}/{} ({:.1f}%)",
                                    formatBytes(downloadedBytes), formatBytes(totalBytes),
                                    (downloadedBytes * 100.0) / totalBytes);
                            } else {
                                logger.info("Downloaded {}", formatBytes(downloadedBytes));
                            }
                            lastLogTime = currentTime;
                            lastPercent = currentPercent;
                        }
                    }

                    logger.info("Download completed: {}", formatBytes(downloadedBytes));
                }

                // Atomic rename to final destination
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
                logger.debug("Moved downloaded file to: {}", destination);

            } catch (Exception e) {
                // Clean up the partial temp file so multi-GB .tmp files don't accumulate
                // on repeated download failures (disk-full, network errors, etc.)
                try { Files.deleteIfExists(tempFile); }
                catch (IOException ignored) { /* best-effort cleanup */ }
                throw e;
            }

        } catch (Exception e) {
            logger.error("Download failed for URL: {}", url, e);
            throw e;
        }
    }

    /**
     * Check if a model is fully downloaded
     * @param modelType The model to check
     * @return true if model exists locally
     */
    public static boolean isModelDownloaded(ModelManager.ModelType modelType) {
        ModelManager manager = ModelManager.getInstance();
        Path modelPath = manager.getModelPath(modelType);
        return Files.exists(modelPath);
    }

    /**
     * Get the download size estimate for a model
     * @param modelType The model type
     * @return Estimated size in MB
     */
    public static int getEstimatedSizeMB(ModelManager.ModelType modelType) {
        return modelType.getSizeMB();
    }

    /**
     * Format bytes to human-readable string
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
