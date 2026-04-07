package com.automationanywhere.botcommand.utils;

import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager for handling llama.cpp model lifecycle across bot sessions.
 * Ensures models are loaded once and reused for better performance.
 * Thread-safe implementation for concurrent bot execution.
 *
 * Uses java-llama.cpp library which supports:
 * - Windows (x64)
 * - macOS (Intel x86-64 + Apple Silicon ARM64)
 * - Linux (x86-64, aarch64)
 * - CPU-only inference (no GPU required)
 * - GGUF model format (quantized for efficiency)
 */
public class ModelManager {
    private static final Logger logger = LogManager.getLogger(ModelManager.class);
    private static volatile ModelManager instance;

    // Model cache directory - cross-platform using user.home
    private static final Path MODEL_CACHE_DIR = Paths.get(
        System.getProperty("user.home"),
        ".aa-slm-models"
    );

    // Loaded models cache (LlamaModel instances)
    private final Map<String, LlamaModel> loadedModels;

    /**
     * Model configuration with GGUF format support.
     *
     * To add a new model, update this enum only:
     * - id / directory / file name
     * - download URL
     * - prompt template profile
     */
    public enum ModelType {
        // Gemma 2 2B - Q4_K_M quantization (~1.7GB)
        GEMMA_2B(
            "gemma-2b",
            "gemma-2b-q4",
            "gemma-2-2b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            1700,
            8192,
            4096,
            PromptTemplate.RAW
        ),

        // Qwen2.5 3B - Q4_K_M quantization (~2.1GB, optimized for structured data/JSON)
        QWEN2_5_3B(
            "qwen2.5-3b",
            "qwen2.5-3b-q4",
            "qwen2.5-3b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            2100,
            131072,
            4096,
            PromptTemplate.CHATML
        ),

        // Llama 3.2 3B Instruct - Q4_K_M quantization (~2.0GB)
        LLAMA3_2_3B(
            "llama3.2-3b",
            "llama3.2-3b-q4",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2020,
            8192,
            4096,
            PromptTemplate.RAW
        ),

        // Phi-3.5 Mini Instruct - Q4_K_M quantization (~2.23GB)
        PHI3_5_MINI(
            "phi3.5-mini",
            "phi3.5-mini-q4",
            "Phi-3.5-mini-instruct.Q4_K_M.gguf",
            "https://huggingface.co/RichardErkhov/microsoft_-_Phi-3.5-mini-instruct-gguf/resolve/main/Phi-3.5-mini-instruct.Q4_K_M.gguf",
            2230,
            131072,
            8192,
            PromptTemplate.RAW
        ),

        // Gemma 4 E2B Instruct - Q4_K_M quantization (~3.11GB, 5.1B params, ~2.3B active)
        GEMMA4_E2B(
            "gemma4-e2b",
            "gemma4-e2b-q4",
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
            3185,
            131072,
            4096,
            PromptTemplate.GEMMA4
        ),

        // DeepSeek R1 Distill Qwen 1.5B - Q4_K_M quantization (~1.12GB, reasoning model)
        DEEPSEEK_R1_1_5B(
            "deepseek-r1-1.5b",
            "deepseek-r1-1.5b-q4",
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            1147,
            131072,
            4096,
            PromptTemplate.CHATML
        );

        public enum PromptTemplate {
            RAW,
            CHATML,
            GEMMA4
        }

        private final String id;
        private final String dirName;
        private final String fileName;
        private final String downloadUrl;
        private final int sizeMB;
        private final int contextWindow;
        private final int maxOutputTokens;
        private final PromptTemplate promptTemplate;

        ModelType(String id, String dirName, String fileName, String downloadUrl, int sizeMB, int contextWindow, int maxOutputTokens, PromptTemplate promptTemplate) {
            this.id = id;
            this.dirName = dirName;
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.sizeMB = sizeMB;
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            this.promptTemplate = promptTemplate;
        }

        public String getId() { return id; }
        public String getDirName() { return dirName; }
        public String getFileName() { return fileName; }
        public String getDownloadUrl() { return downloadUrl; }
        public int getSizeMB() { return sizeMB; }
        public int getContextWindow() { return contextWindow; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public PromptTemplate getPromptTemplate() { return promptTemplate; }

        public static ModelType fromId(String id) {
            for (ModelType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown model type: " + id);
        }

        public static String supportedModelIds() {
            StringBuilder ids = new StringBuilder();
            for (ModelType type : values()) {
                if (ids.length() > 0) {
                    ids.append(", ");
                }
                ids.append("'").append(type.getId()).append("'");
            }
            return ids.toString();
        }
    }

    /**
     * Private constructor for singleton pattern
     */
    private ModelManager() {
        this.loadedModels = new ConcurrentHashMap<>();

        logger.info("ModelManager initialized with llama.cpp");
        logger.info("Model cache directory: {}", MODEL_CACHE_DIR.toAbsolutePath());

        // Ensure cache directory exists
        ensureCacheDirectoryExists();
    }

    /**
     * Get singleton instance using double-checked locking
     */
    public static ModelManager getInstance() {
        if (instance == null) {
            synchronized (ModelManager.class) {
                if (instance == null) {
                    instance = new ModelManager();
                }
            }
        }
        return instance;
    }

    /**
     * Get or load a model
     * @param modelType The type of model to load
     * @return LlamaModel for the requested model
     * @throws Exception if model cannot be loaded
     */
    public LlamaModel getModel(ModelType modelType) throws Exception {
        String modelKey = modelType.getId();

        // Check if already loaded
        if (loadedModels.containsKey(modelKey)) {
            logger.debug("Model {} already loaded, returning cached instance", modelKey);
            return loadedModels.get(modelKey);
        }

        // Load model
        synchronized (this) {
            // Double-check after acquiring lock
            if (loadedModels.containsKey(modelKey)) {
                return loadedModels.get(modelKey);
            }

            logger.info("Loading model: {}", modelKey);
            Path modelPath = getModelPath(modelType);

            // Check if model exists locally
            if (!Files.exists(modelPath)) {
                logger.info("Model not found at {}, downloading...", modelPath);
                ModelDownloader.downloadModel(modelType);
            }

            // Verify model file exists after download
            if (!Files.exists(modelPath)) {
                throw new RuntimeException("Model file not found after download: " + modelPath);
            }

            // Detect OS for logging
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            logger.info("Loading on OS: {}, Architecture: {}", os, arch);

            try {
                // Set a reasonable context size to avoid excessive memory allocation
                // For Qwen2.5 with 128K max context, we limit to 8K for practical use
                // This prevents huge KV cache allocation that causes timeouts
                int contextSize = Math.min(modelType.getContextWindow(), 8192);
                if (modelType.getContextWindow() > 8192) {
                    logger.info("Limiting context size to 8192 (model supports up to {})", modelType.getContextWindow());
                }

                // Create model parameters (CPU-only, no GPU layers)
                ModelParameters modelParams = new ModelParameters()
                    .setModel(modelPath.toString())
                    .setGpuLayers(0)  // CPU only - works on all platforms
                    .setCtxSize(contextSize);  // Limit context to prevent OOM and timeouts

                // Load the model
                LlamaModel model = new LlamaModel(modelParams);

                loadedModels.put(modelKey, model);
                logger.info("Model {} loaded successfully from {}", modelKey, modelPath);

                return model;

            } catch (Exception e) {
                logger.error("Failed to load model {}", modelKey, e);
                throw new RuntimeException("Failed to load model: " + modelKey, e);
            }
        }
    }

    /**
     * Check if a model is currently loaded
     * @param modelType The model type to check
     * @return true if loaded, false otherwise
     */
    public boolean isModelLoaded(ModelType modelType) {
        return loadedModels.containsKey(modelType.getId());
    }

    /**
     * Unload a specific model to free memory
     * @param modelType The model to unload
     */
    public void unloadModel(ModelType modelType) {
        String modelKey = modelType.getId();
        synchronized (this) {
            LlamaModel model = loadedModels.remove(modelKey);
            if (model != null) {
                try {
                    model.close();
                    logger.info("Model {} unloaded successfully", modelKey);
                } catch (Exception e) {
                    logger.warn("Error closing model {}", modelKey, e);
                }
            }
        }
    }

    /**
     * Unload all models and clean up resources
     */
    public void shutdown() {
        synchronized (this) {
            logger.info("Shutting down ModelManager, unloading all models...");
            for (Map.Entry<String, LlamaModel> entry : loadedModels.entrySet()) {
                try {
                    entry.getValue().close();
                    logger.debug("Closed model {}", entry.getKey());
                } catch (Exception e) {
                    logger.warn("Error closing model {}", entry.getKey(), e);
                }
            }
            loadedModels.clear();
        }
    }

    /**
     * Get the file path for a model (cross-platform)
     */
    public Path getModelPath(ModelType modelType) {
        return MODEL_CACHE_DIR.resolve(modelType.getDirName()).resolve(modelType.getFileName());
    }

    /**
     * Get the directory path for a model (cross-platform)
     */
    public Path getModelDirectory(ModelType modelType) {
        return MODEL_CACHE_DIR.resolve(modelType.getDirName());
    }

    /**
     * Ensure the cache directory exists (cross-platform)
     */
    private void ensureCacheDirectoryExists() {
        try {
            if (!Files.exists(MODEL_CACHE_DIR)) {
                Files.createDirectories(MODEL_CACHE_DIR);
                logger.info("Created model cache directory: {}", MODEL_CACHE_DIR);
            }
        } catch (Exception e) {
            logger.error("Failed to create model cache directory", e);
            throw new RuntimeException("Failed to create cache directory", e);
        }
    }

    /**
     * Get model cache directory
     */
    public static Path getModelCacheDir() {
        return MODEL_CACHE_DIR;
    }
}
