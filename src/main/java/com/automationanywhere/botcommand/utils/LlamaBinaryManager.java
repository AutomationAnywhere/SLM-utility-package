package com.automationanywhere.botcommand.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
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

    /**
     * Ensure the llama-server binary is present and up-to-date.
     *
     * Synchronized on the class to prevent two bot threads from racing through
     * the Files.exists() check and writing the same files concurrently, which
     * can corrupt the binary on Windows (open-file contention).
     */
    public static synchronized void ensureInstalled() throws Exception {
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
                logger.debug("llama-server already installed at: {}", serverPath);
                return;
            }
        }

        Files.createDirectories(BIN_DIR);

        // actualTag is the tag that was actually installed (embedded or downloaded).
        // This may differ from embeddedTag when the embedded resource is missing and
        // the fallback downloads a newer release — write the real tag so that version
        // staleness detection works correctly on the next JVM startup.
        final String actualTag;
        if (embeddedTag != null) {
            logger.info("Extracting embedded llama-server ({})...", embeddedTag);
            actualTag = extractEmbeddedArchive(embeddedTag);
        } else {
            logger.info("No embedded binary found — downloading official llama.cpp CPU release...");
            actualTag = fetchLatestTag();
            logger.info("Downloading llama.cpp release: {}", actualTag);
            downloadAndExtract(actualTag);
        }
        Files.writeString(versionFile, actualTag);

        // Set executable bit on Mac/Linux
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            serverPath.toFile().setExecutable(true, false);
        }

        if (!Files.exists(serverPath)) {
            throw new RuntimeException("llama-server binary not found after installation. Check logs.");
        }
        logger.info("llama-server {} installed at: {}", actualTag, serverPath);
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
     * Falls back to a GitHub download if the resource is unexpectedly absent
     * (e.g. a partial build where version.txt was embedded but the archive was not).
     *
     * @param embeddedTag the tag recorded in the JAR's version.txt
     * @return the tag that was actually installed (may differ from embeddedTag if
     *         the fallback GitHub download fetched a newer release)
     */
    private static String extractEmbeddedArchive(String embeddedTag) throws Exception {
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
                // Partial/inconsistent build: version.txt present but archive absent.
                // Fall back to GitHub — return the actual downloaded tag so the caller
                // writes the correct value to version.txt.
                logger.warn("Embedded resource {} not found — falling back to GitHub download", resourceName);
                String downloadedTag = fetchLatestTag();
                downloadAndExtract(downloadedTag);
                return downloadedTag; // <-- return real tag, not embeddedTag
            }

            // Copy resource stream to a temp file, then reuse the existing extraction methods.
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
        return embeddedTag; // embedded archive extracted successfully
    }

    /**
     * Fetches the latest llama.cpp release tag from the GitHub API.
     * Uses Gson for robust JSON parsing instead of fragile string indexOf —
     * the indexOf approach breaks silently if GitHub ever adds whitespace after
     * the colon in {"tag_name": "bXXXX"} (valid JSON, different string layout).
     */
    private static String fetchLatestTag() throws Exception {
        String apiUrl = "https://api.github.com/repos/ggerganov/llama.cpp/releases/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream is = conn.getInputStream()) {
            String json = new String(is.readAllBytes());
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement tagEl = obj.get("tag_name");
            if (tagEl == null || tagEl.isJsonNull()) {
                throw new RuntimeException(
                    "GitHub API response missing 'tag_name' field. Response: " + json);
            }
            return tagEl.getAsString();
        } finally {
            conn.disconnect();
        }
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

    /**
     * Extracts all file entries from a ZIP into BIN_DIR, stripping any
     * subdirectory path components so everything lands flat in BIN_DIR.
     *
     * Path-safety: after resolving the destination we verify it is still under
     * BIN_DIR (canonical comparison), guarding against Zip Slip attacks where
     * a crafted archive entry uses ".." sequences or absolute paths to escape
     * the target directory.
     */
    private static void extractZip(Path zipFile) throws Exception {
        Path canonicalBinDir = BIN_DIR.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                // Use only the bare filename — strips any leading path components
                String name = Paths.get(entry.getName()).getFileName().toString();
                Path dest = BIN_DIR.resolve(name).toAbsolutePath().normalize();
                // Zip Slip guard: reject entries whose resolved path escapes BIN_DIR
                if (!dest.startsWith(canonicalBinDir)) {
                    logger.warn("Skipping suspicious zip entry (path escape attempt): {}", entry.getName());
                    zis.closeEntry();
                    continue;
                }
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
