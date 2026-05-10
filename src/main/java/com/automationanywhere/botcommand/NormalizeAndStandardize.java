package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.DictionaryHelper;
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

import java.util.LinkedHashMap;

import static com.automationanywhere.commandsdk.model.DataType.DICTIONARY;

/**
 * NormalizeAndStandardize Action
 *
 * Cleans and standardizes inconsistent data formats using Small Language Models.
 * Perfect for:
 * - Phone numbers: "(555) 123-4567" → "5551234567"
 * - Dates: "March 15, 2024" → "2024-03-15"
 * - Addresses: Various formats → standard format
 * - Names: "SMITH, JOHN Q." → "John Q. Smith"
 * - Custom formats: Any text normalization task
 *
 * Returns a Dictionary with keys:
 * - result: the normalized/standardized text
 * - original: the original input text
 * - data_type: the data type used for normalization
 * - status: "success" or "error"
 * - message: timing and model info
 *
 * Data never leaves your device. No API keys. No cloud calls.
 *
 * First execution downloads model (~600MB-5GB) and may take 10-30 seconds for loading.
 * Subsequent calls are faster (<5 seconds) as models stay in memory.
 */
@BotCommand
@CommandPkg(
    label = "Normalize And Standardize",
    name = "normalizeAndStandardize",
    description = "Cleans and standardizes inconsistent data formats using on-device Small Language Model",
    node_label = "Normalize: {{inputText}}",
    icon = "pkg.svg",
    comment = true,
    text_color = "#2C3E50",
    background_color = "#E3F2FD",
    allowed_agent_targets = {
        AllowedTarget.WINDOWS,
        AllowedTarget.MAC_OS
    },
    return_type = DICTIONARY,
    return_required = true
)
public class NormalizeAndStandardize {

    private static final Logger logger = LogManager.getLogger(NormalizeAndStandardize.class);

    // Max tokens for normalized output. Set above the typical single-field result (phone/date/name)
    // to allow room for longer types like addresses or custom formats without truncating.
    // Capped at the model's own limit via Math.min at call time.
    static final int MAX_OUTPUT_TOKENS = 200;

    // Low temperature for consistent, deterministic formatting output.
    static final float NORMALIZE_TEMPERATURE = 0.1f;

    /**
     * Execute text normalization and standardization using a Small Language Model
     *
     * @param inputText The text to normalize/standardize
     * @param dataType Type of data: "phone", "date", "address", "name", "email", "auto-detect", "custom"
     * @param outputFormat Target format (e.g., "YYYY-MM-DD" for dates, "E.164" for phones, or custom format)
     * @param modelName The model to use (qwen2.5-3b, llama3.2-3b, phi3.5-mini, gemma-2b)
     * @param preserveOriginalOnFailure If true, return original text when normalization fails
     * @param timeoutSeconds Maximum time to wait for processing (default: 30)
     * @return Normalized/standardized text
     */
    @Execute
    public DictionaryValue execute(

        @Idx(index = "1", type = AttributeType.TEXTAREA)
        @Pkg(label = "Input Text", description = "Text to normalize/standardize")
        @NotEmpty
        String inputText,

        @Idx(index = "2", type = AttributeType.SELECT, options = {
            @Idx.Option(index = "2.1", pkg = @Pkg(label = "Phone Number", value = "phone")),
            @Idx.Option(index = "2.2", pkg = @Pkg(label = "Date", value = "date")),
            @Idx.Option(index = "2.3", pkg = @Pkg(label = "Address", value = "address")),
            @Idx.Option(index = "2.4", pkg = @Pkg(label = "Name", value = "name")),
            @Idx.Option(index = "2.5", pkg = @Pkg(label = "Email", value = "email")),
            @Idx.Option(index = "2.6", pkg = @Pkg(label = "Auto-Detect", value = "auto-detect")),
            @Idx.Option(index = "2.7", pkg = @Pkg(label = "Custom", value = "custom"))
        })
        @Pkg(label = "Data Type", description = "Type of data to normalize", default_value = "auto-detect", default_value_type = DataType.STRING)
        @NotEmpty
        String dataType,

        @Idx(index = "3", type = AttributeType.TEXT)
        @Pkg(label = "Output Format", description = "Target format (e.g., 'YYYY-MM-DD', 'E.164', 'digits only', or describe custom format)")
        String outputFormat,

        @Idx(index = "4", type = AttributeType.SELECT, options = {
            @Idx.Option(index = "4.1", pkg = @Pkg(label = "Qwen2.5 3B (Q4, ~2.1GB, 128K context)", value = "qwen2.5-3b")),
            @Idx.Option(index = "4.2", pkg = @Pkg(label = "Llama 3.2 3B (Q4, ~2.0GB)", value = "llama3.2-3b")),
            @Idx.Option(index = "4.3", pkg = @Pkg(label = "Phi-3.5 Mini (Q4, ~2.23GB, 128K context)", value = "phi3.5-mini")),
            @Idx.Option(index = "4.4", pkg = @Pkg(label = "Gemma 2B (Q4, ~1.7GB)", value = "gemma-2b")),
            @Idx.Option(index = "4.5", pkg = @Pkg(label = "Gemma 4 E2B (Q4, ~3.1GB, 128K context)", value = "gemma4-e2b")),
            @Idx.Option(index = "4.6", pkg = @Pkg(label = "DeepSeek R1 1.5B (Q4, ~1.1GB, reasoning)", value = "deepseek-r1-1.5b"))
        })
        @Pkg(label = "Model", description = "Small Language Model to use for normalization (curated top models under 3GB).", default_value = "qwen2.5-3b", default_value_type = DataType.STRING)
        @NotEmpty
        String modelName,

        @Idx(index = "5", type = AttributeType.CHECKBOX)
        @Pkg(label = "Preserve Original On Failure", description = "Return original text if normalization fails", default_value = "true", default_value_type = DataType.BOOLEAN)
        Boolean preserveOriginalOnFailure,

        @Idx(index = "6", type = AttributeType.NUMBER)
        @Pkg(label = "Timeout (seconds)", description = "Maximum time to wait for processing", default_value = "30", default_value_type = DataType.NUMBER)
        @NotEmpty
        Double timeoutSeconds

    ) {

        logger.info("NormalizeAndStandardize action started - Model: {}, DataType: {}, Format: {}, Timeout: {}s",
                    modelName, dataType, outputFormat, timeoutSeconds);

        // Declared outside try so they're accessible in the preserveOriginalOnFailure catch path
        ModelManager.ModelType modelType = null;
        long startTime = System.currentTimeMillis();

        try {
            // Validate inputs
            if (inputText == null || inputText.trim().isEmpty()) {
                throw new BotCommandException("Input text cannot be empty");
            }

            if (dataType == null || dataType.trim().isEmpty()) {
                dataType = "auto-detect";
            }

            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 30.0;
            }

            if (preserveOriginalOnFailure == null) {
                preserveOriginalOnFailure = true;
            }

            // Parse model type
            try {
                modelType = ModelManager.ModelType.fromId(modelName.toLowerCase().trim());
            } catch (IllegalArgumentException e) {
                logger.error("Invalid model name: {}", modelName);
                throw new BotCommandException("Invalid model name: " + modelName +
                    ". Valid options: " + ModelManager.ModelType.supportedModelIds());
            }

            logger.debug("Using model: {} for text length: {}", modelType.getId(), inputText.length());

            // Initialize inference engine (will load model if needed)
            startTime = System.currentTimeMillis();
            LlamaInference inference = new LlamaInference(modelType);
            long loadTime = System.currentTimeMillis() - startTime;

            if (loadTime > 1000) {
                logger.info("Model loaded in {}ms (first-time loading takes longer)", loadTime);
            }

            // Build normalization prompt
            String prompt = buildNormalizationPrompt(inputText, dataType, outputFormat);
            logger.debug("Generated prompt: {}", prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

            // Generate normalized text — cap to the model's own max output token limit
            int effectiveMaxTokens = Math.min(MAX_OUTPUT_TOKENS, modelType.getMaxOutputTokens());
            String rawResponse = inference.generateText(
                prompt,
                effectiveMaxTokens,
                NORMALIZE_TEMPERATURE,
                timeoutSeconds.intValue()
            );

            // Parse and validate the response
            String result = parseNormalizationResponse(rawResponse, inputText, preserveOriginalOnFailure);

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Normalization completed in {}ms. Input: '{}' → Output: '{}'",
                       totalTime, inputText, result);

            LinkedHashMap<String, Value<?>> fields = new LinkedHashMap<>();
            fields.put("result", new StringValue(result));
            fields.put("original", new StringValue(inputText));
            fields.put("data_type", new StringValue(dataType));
            return DictionaryHelper.success(fields, modelType.getId(), totalTime);

        } catch (Exception e) {
            logger.error("NormalizeAndStandardize action failed", e);

            // If preserve original is enabled, return original text
            if (preserveOriginalOnFailure != null && preserveOriginalOnFailure) {
                logger.warn("Returning original text due to failure: {}", e.getMessage());
                LinkedHashMap<String, Value<?>> fields = new LinkedHashMap<>();
                fields.put("result", new StringValue(inputText));
                fields.put("original", new StringValue(inputText));
                fields.put("data_type", new StringValue(dataType != null ? dataType : "unknown"));
                String resolvedModel = modelType != null ? modelType.getId() : modelName;
                return DictionaryHelper.success(fields, resolvedModel, System.currentTimeMillis() - startTime);
            }

            // Provide helpful error messages
            String errorMsg;
            if (e.getMessage().contains("timeout")) {
                errorMsg = "Normalization timed out after " + timeoutSeconds + " seconds. Try increasing timeout or using a smaller model.";
            } else if (e.getMessage().contains("model") || e.getMessage().contains("GGUF")) {
                errorMsg = "Model loading/inference failed: " + e.getMessage() +
                    ". Ensure sufficient memory (~8GB RAM) and model is downloaded.";
            } else {
                errorMsg = "Normalization failed: " + e.getMessage();
            }

            logger.error("Error details: {}", errorMsg);
            throw new BotCommandException(errorMsg, e);
        }
    }

    /**
     * Build the normalization prompt based on data type and format
     */
    private String buildNormalizationPrompt(String inputText, String dataType, String outputFormat) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Normalize and standardize the following text.\n\n");

        // Add specific instructions based on data type
        switch (dataType.toLowerCase().trim()) {
            case "phone":
                prompt.append("Data type: Phone number\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: Remove all formatting, return digits only\n");
                }
                prompt.append("Examples:\n");
                prompt.append("  (555) 123-4567 → 5551234567\n");
                prompt.append("  555-123-4567 → 5551234567\n");
                prompt.append("  +1-555-123-4567 → 15551234567\n");
                break;

            case "date":
                prompt.append("Data type: Date\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: YYYY-MM-DD\n");
                }
                prompt.append("Examples:\n");
                prompt.append("  March 15, 2024 → 2024-03-15\n");
                prompt.append("  03/15/2024 → 2024-03-15\n");
                prompt.append("  15-Mar-2024 → 2024-03-15\n");
                break;

            case "address":
                prompt.append("Data type: Address\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: Standard format with proper capitalization and abbreviations\n");
                }
                prompt.append("Examples:\n");
                prompt.append("  123 main st → 123 Main St\n");
                prompt.append("  456 PARK AVENUE APT 5B → 456 Park Avenue Apt 5B\n");
                break;

            case "name":
                prompt.append("Data type: Name\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: Proper case with first name, middle initial/name, last name\n");
                }
                prompt.append("Examples:\n");
                prompt.append("  SMITH, JOHN Q. → John Q. Smith\n");
                prompt.append("  john smith → John Smith\n");
                prompt.append("  JANE DOE → Jane Doe\n");
                break;

            case "email":
                prompt.append("Data type: Email address\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: Lowercase, trimmed\n");
                }
                prompt.append("Examples:\n");
                prompt.append("  John.Doe@EXAMPLE.COM → john.doe@example.com\n");
                prompt.append("   user@domain.com  → user@domain.com\n");
                break;

            case "custom":
                prompt.append("Data type: Custom\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format requirement: ").append(outputFormat).append("\n");
                } else {
                    prompt.append("Format: Clean and standardize the text\n");
                }
                break;

            case "auto-detect":
            default:
                prompt.append("Data type: Auto-detect\n");
                prompt.append("Detect the type of data and normalize it appropriately.\n");
                if (outputFormat != null && !outputFormat.trim().isEmpty()) {
                    prompt.append("Format requirement: ").append(outputFormat).append("\n");
                }
                break;
        }

        prompt.append("\nInput text: ").append(inputText).append("\n");
        prompt.append("\nOutput ONLY the normalized text, nothing else:\n");

        return prompt.toString();
    }

    /**
     * Parse the model's normalization response.
     * Package-private for unit testing.
     */
    String parseNormalizationResponse(String rawResponse, String originalText, boolean preserveOriginal) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            logger.warn("Empty response from model");
            return preserveOriginal ? originalText : "";
        }

        String cleaned = rawResponse.trim();

        // Strip DeepSeek R1 thinking blocks (<think>...</think>)
        cleaned = LlamaInference.stripThinkingBlocks(cleaned);

        // Remove common prefixes the model might add
        cleaned = cleaned.replaceAll("^(Output:|Result:|Normalized:|Answer:)\\s*", "");

        // Take only the first line (model might add extra commentary)
        if (cleaned.contains("\n")) {
            String firstLine = cleaned.substring(0, cleaned.indexOf("\n")).trim();
            if (!firstLine.isEmpty()) {
                cleaned = firstLine;
            }
        }

        return cleaned;
    }
}
