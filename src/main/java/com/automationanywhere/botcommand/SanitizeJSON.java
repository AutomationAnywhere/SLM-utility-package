package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.ModelManager;
import com.automationanywhere.botcommand.utils.LlamaInference;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.automationanywhere.commandsdk.model.DataType.STRING;

/**
 * SanitizeJSON Action
 *
 * Uses Small Language Models (TinyLlama, Gemma) running on-device via llama.cpp to sanitize text for JSON compatibility.
 * Models run on CPU only and work cross-platform using GGUF quantized models.
 *
 * Cross-platform support:
 * - Windows (x64)
 * - macOS (Intel x64 and Apple Silicon ARM64)
 * - Linux (x64, aarch64)
 *
 * First execution downloads model (~600MB-5GB depending on choice) and may take 10-30 seconds for loading.
 * Subsequent calls are faster (<5 seconds) as models stay in memory.
 */
@BotCommand
@CommandPkg(
    label = "Sanitize JSON Text",
    name = "sanitizeJSON",
    description = "Uses on-device Small Language Model to sanitize text for JSON compatibility",
    node_label = "Sanitize JSON: {{inputText}}",
    icon = "pkg.svg",
    comment = true,
    text_color = "#2C3E50",
    background_color = "#ECF0F1",
    allowed_agent_targets = {
//                AllowedTarget.HEADLESS,
            AllowedTarget.WINDOWS,
            AllowedTarget.MAC_OS
//                AllowedTarget.ONDEMAND_CLOUD
    },
    return_type = STRING,
    return_required = true
)
public class SanitizeJSON {

    private static final Logger logger = LogManager.getLogger(SanitizeJSON.class);

    /**
     * Execute the JSON sanitization using a Small Language Model
     *
     * @param inputText The text to sanitize for JSON compatibility
     * @param modelName The model ID to use
     * @param timeoutSeconds Maximum time to wait for processing (default: 30)
     * @return Sanitized text safe for JSON
     */
    @Execute
    public Value<String> execute(

        @Idx(index = "1", type = AttributeType.TEXT)
        @Pkg(label = "Input Text", description = "Text to sanitize for JSON compatibility")
        @NotEmpty
        String inputText,

        @Idx(index = "2", type = AttributeType.SELECT, options = {
            @Idx.Option(index = "2.1", pkg = @Pkg(label = "Qwen2.5 3B (Q4, ~2.1GB, 128K context)", value = "qwen2.5-3b")),
            @Idx.Option(index = "2.2", pkg = @Pkg(label = "Llama 3.2 3B (Q4, ~2.0GB)", value = "llama3.2-3b")),
            @Idx.Option(index = "2.3", pkg = @Pkg(label = "Phi-3.5 Mini (Q4, ~2.23GB, 128K context)", value = "phi3.5-mini")),
            @Idx.Option(index = "2.4", pkg = @Pkg(label = "Gemma 2B (Q4, ~1.7GB)", value = "gemma-2b")),
            @Idx.Option(index = "2.5", pkg = @Pkg(label = "Gemma 4 E2B (Q4, ~3.1GB, 128K context)", value = "gemma4-e2b")),
            @Idx.Option(index = "2.6", pkg = @Pkg(label = "DeepSeek R1 1.5B (Q4, ~1.1GB, reasoning)", value = "deepseek-r1-1.5b"))
        })
        @Pkg(label = "Model", description = "Small Language Model to use for sanitization (curated top models under 3GB).", default_value = "qwen2.5-3b", default_value_type = DataType.STRING)
        @NotEmpty
        String modelName,

        @Idx(index = "3", type = AttributeType.NUMBER)
        @Pkg(label = "Timeout (seconds)", description = "Maximum time to wait for processing", default_value = "30", default_value_type = DataType.NUMBER)
        @NotEmpty
        Double timeoutSeconds

    ) {

        logger.info("SanitizeJSON action started - Model: {}, Timeout: {}s", modelName, timeoutSeconds);

        try {
            // Validate inputs
            if (inputText == null || inputText.trim().isEmpty()) {
                throw new BotCommandException("Input text cannot be empty");
            }

            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 30.0;
            }

            // Parse model type
            ModelManager.ModelType modelType;
            try {
                modelType = ModelManager.ModelType.fromId(modelName.toLowerCase().trim());
            } catch (IllegalArgumentException e) {
                logger.error("Invalid model name: {}", modelName);
                throw new BotCommandException("Invalid model name: " + modelName +
                    ". Valid options: " + ModelManager.ModelType.supportedModelIds());
            }

            logger.debug("Using model: {} for input length: {}", modelType.getId(), inputText.length());

            // Initialize inference engine (will load model if needed)
            long startTime = System.currentTimeMillis();
            LlamaInference inference = new LlamaInference(modelType);
            long loadTime = System.currentTimeMillis() - startTime;

            if (loadTime > 1000) {
                logger.info("Model loaded in {}ms (first-time loading takes longer)", loadTime);
            }

            // Generate sanitized text
            String sanitized = inference.generateSanitizedJSON(inputText, timeoutSeconds.intValue());

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Sanitization completed in {}ms", totalTime);

            return new StringValue(sanitized);

        } catch (Exception e) {
            logger.error("SanitizeJSON action failed", e);

            // Provide helpful error messages
            String errorMsg;
            if (e.getMessage().contains("timeout")) {
                errorMsg = "Processing timed out after " + timeoutSeconds + " seconds. Try increasing timeout or using a smaller model.";
            } else if (e.getMessage().contains("model") || e.getMessage().contains("GGUF")) {
                errorMsg = "Model loading/inference failed: " + e.getMessage() +
                    ". Ensure sufficient memory (~8GB RAM) and model file is available.";
            } else {
                errorMsg = "Sanitization failed: " + e.getMessage();
            }

            logger.error("Error details: {}", errorMsg);
            throw new BotCommandException(errorMsg, e);

        }
        // Note: Models stay loaded in ModelManager for reuse across calls
    }
}
