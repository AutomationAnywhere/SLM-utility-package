package com.automationanywhere.botcommand.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Downloads and manages the official llama.cpp binary package.
 *
 * The official CPU release zips/tarballs (9-15MB) are downloaded once to
 * {modelCacheDir}/bin/ and reused across bot runs. This avoids the JNI
 * binding approach (de.kherud:llama) which was pinned to llama.cpp b4916
 * (March 2025) and does not support modern models like Qwen3 or Gemma4.
 *
 * Platforms:
 *   Windows x64:  llama-{tag}-bin-win-cpu-x64.zip   (~15MB)
 *   macOS ARM64:  llama-{tag}-bin-macos-arm64.tar.gz (~9MB)
 *   macOS x86_64: llama-{tag}-bin-macos-x64.tar.gz   (~10MB)
 */
public class LlamaBinaryManager {
    private static final Logger logger = LogManager.getLogger(LlamaBinaryManager.class);

    private static final Path BIN_DIR = ModelManager.getModelCacheDir().resolve("bin");

    public static Path getBinDir() {
        return BIN_DIR;
    }

    public static Path getLlamaServerPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return BIN_DIR.resolve(os.contains("windows") ? "llama-server.exe" : "llama-server");
    }

    public static void ensureInstalled() throws Exception {
        if (Files.exists(getLlamaServerPath())) {
            logger.debug("llama-server already installed at: {}", getLlamaServerPath());
            return;
        }

        logger.info("llama-server not found — downloading official llama.cpp CPU release...");
        Files.createDirectories(BIN_DIR);

        String tag = fetchLatestTag();
        logger.info("Latest llama.cpp release: {}", tag);
        downloadAndExtract(tag);

        // Set executable bit on Mac/Linux
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            getLlamaServerPath().toFile().setExecutable(true, false);
        }

        if (!Files.exists(getLlamaServerPath())) {
            throw new RuntimeException("llama-server binary not found after extraction. Check logs.");
        }
        logger.info("llama-server installed at: {}", getLlamaServerPath());
    }

    private static String fetchLatestTag() throws Exception {
        String apiUrl = "https://api.github.com/repos/ggerganov/llama.cpp/releases/latest";
        URL url = new URL(apiUrl);
        String json;
        try (InputStream is = url.openStream()) {
            json = new String(is.readAllBytes());
        }
        int idx = json.indexOf("\"tag_name\":\"");
        if (idx < 0) throw new RuntimeException("Could not parse tag_name from GitHub API");
        int start = idx + 12;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static void downloadAndExtract(String tag) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        boolean isWindows = os.contains("windows");
        String platform;
        boolean isZip;

        if (isWindows) {
            platform = "win-cpu-x64";
            isZip = true;
        } else if (arch.contains("aarch64") || arch.contains("arm")) {
            platform = "macos-arm64";
            isZip = false;
        } else {
            platform = "macos-x64";
            isZip = false;
        }

        String filename = String.format("llama-%s-bin-%s%s", tag, platform, isZip ? ".zip" : ".tar.gz");
        String downloadUrl = String.format(
            "https://github.com/ggerganov/llama.cpp/releases/download/%s/%s", tag, filename);

        logger.info("Downloading: {} (~9-15MB)", downloadUrl);

        Path tmp = Files.createTempFile("llama-bin-", isZip ? ".zip" : ".tar.gz");
        try {
            download(downloadUrl, tmp);
            logger.info("Download complete ({}MB). Extracting...",
                Math.round(Files.size(tmp) / 1_048_576.0));

            if (isZip) {
                extractZip(tmp);
            } else {
                extractTarGz(tmp);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void download(String urlStr, Path dest) throws Exception {
        URL url = new URL(urlStr);
        try (InputStream in = url.openStream();
             OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                total += n;
                if (total % (2 * 1024 * 1024) < 65536) {
                    logger.info("  ... {}MB", total / 1_048_576);
                }
            }
        }
    }

    /** Extract all entries from a ZIP file directly into BIN_DIR (no subdirectory prefix). */
    private static void extractZip(Path zipFile) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = Paths.get(entry.getName()).getFileName().toString(); // strip any subdirs
                Path dest = BIN_DIR.resolve(name);
                Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
        }
        logger.info("Extracted {} to: {}", zipFile.getFileName(), BIN_DIR);
    }

    /**
     * Extract tar.gz using the system `tar` command (always available on Mac).
     * The Mac tarballs have files under a `llama-{tag}/` subdirectory; --strip-components=1
     * removes that prefix so files land directly in BIN_DIR.
     */
    private static void extractTarGz(Path tarFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "tar", "xf", tarFile.toAbsolutePath().toString(),
            "-C", BIN_DIR.toAbsolutePath().toString(),
            "--strip-components=1"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("tar extraction failed (exit " + exitCode + "): " + output);
        }
        logger.info("Extracted {} to: {}", tarFile.getFileName(), BIN_DIR);
    }
}
