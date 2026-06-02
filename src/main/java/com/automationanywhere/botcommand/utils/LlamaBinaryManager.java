package com.automationanywhere.botcommand.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Manages the official llama.cpp binary package.
 *
 * The JAR bundles pre-downloaded llama.cpp CPU release archives under
 * /llama-bin/ (placed there by the Gradle downloadLlamaBinaries task at
 * build time).  On first use, ensureInstalled() extracts the right archive
 * for the current platform into {modelCacheDir}/bin/ and caches it there.
 *
 * If the embedded archive is not present (e.g. a developer build without
 * internet access at build time), the method falls back to downloading the
 * latest release from GitHub — the original behaviour.
 *
 * Platforms:
 *   Windows x64:  llama-bin/windows-x64.zip       (~15MB)
 *   macOS ARM64:  llama-bin/macos-arm64.tar.gz     (~9MB)
 *   macOS x86_64: llama-bin/macos-x64.tar.gz       (~10MB)
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
        Path serverPath = getLlamaServerPath();
        Path versionFile = BIN_DIR.resolve("version.txt");

        // Check if already extracted at the correct version
        String embeddedTag = getEmbeddedTag();
        if (Files.exists(serverPath)) {
            if (embeddedTag != null && Files.exists(versionFile)) {
                String installedTag = Files.readString(versionFile).trim();
                if (installedTag.equals(embeddedTag)) {
                    logger.debug("llama-server {} already installed at: {}", embeddedTag, serverPath);
                    return;
                }
                logger.info("llama-server version mismatch (installed={}, embedded={}) — re-extracting",
                    installedTag, embeddedTag);
            } else if (embeddedTag == null) {
                // No embedded version info — binary present, assume it's fine
                logger.debug("llama-server already installed at: {}", serverPath);
                return;
            }
        }

        Files.createDirectories(BIN_DIR);

        if (embeddedTag != null) {
            // Fast path: extract the archive that was bundled into the JAR at build time
            logger.info("Extracting embedded llama-server ({})...", embeddedTag);
            extractEmbeddedArchive();
            Files.writeString(versionFile, embeddedTag);
        } else {
            // Fallback: download from GitHub (e.g. developer build, no embedded resources)
            logger.info("No embedded binary found — downloading official llama.cpp CPU release...");
            String tag = fetchLatestTag();
            logger.info("Latest llama.cpp release: {}", tag);
            downloadAndExtract(tag);
            Files.writeString(versionFile, tag);
        }

        // Set executable bit on Mac/Linux
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            serverPath.toFile().setExecutable(true, false);
        }

        if (!Files.exists(serverPath)) {
            throw new RuntimeException("llama-server binary not found after installation. Check logs.");
        }
        logger.info("llama-server installed at: {}", serverPath);
    }

    /**
     * Returns the llama.cpp tag bundled in the JAR (from /llama-bin/version.txt),
     * or null if no embedded binaries are present.
     */
    private static String getEmbeddedTag() {
        try (InputStream is = LlamaBinaryManager.class.getResourceAsStream("/llama-bin/version.txt")) {
            if (is == null) return null;
            return new String(is.readAllBytes()).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the platform-appropriate archive bundled in the JAR into BIN_DIR.
     * Falls back to a GitHub download if the resource is unexpectedly absent.
     */
    private static void extractEmbeddedArchive() throws Exception {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        boolean isWindows = os.contains("windows");
        String resourceName;
        boolean isZip;

        if (isWindows) {
            resourceName = "/llama-bin/windows-x64.zip";
            isZip = true;
        } else if (arch.contains("aarch64") || arch.contains("arm")) {
            resourceName = "/llama-bin/macos-arm64.tar.gz";
            isZip = false;
        } else {
            resourceName = "/llama-bin/macos-x64.tar.gz";
            isZip = false;
        }

        logger.info("Extracting embedded resource: {}", resourceName);
        try (InputStream is = LlamaBinaryManager.class.getResourceAsStream(resourceName)) {
            if (is == null) {
                logger.warn("Embedded resource {} not found — falling back to GitHub download", resourceName);
                String tag = fetchLatestTag();
                downloadAndExtract(tag);
                return;
            }

            // Copy resource stream to a temp file, then reuse the existing extraction methods
            String suffix = isZip ? ".zip" : ".tar.gz";
            Path tmp = Files.createTempFile("llama-embedded-", suffix);
            try {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Copied embedded archive: {}MB",
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
