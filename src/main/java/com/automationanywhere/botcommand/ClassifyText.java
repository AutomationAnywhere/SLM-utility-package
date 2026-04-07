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
 * ClassifyText Action
 *
 * Categorizes text into predefined categories using Small Language Models.
 * Perfect for:
 * - Email triage: "urgent", "normal", "spam"
 * - Document routing: "invoice", "receipt", "contract"
 * - Sentiment analysis: "positive", "negative", "neutral"
 * - Priority classification: "high", "medium", "low"
 *
 * Returns classification result as a string in format:
 * - Simple: "urgent"
 * - With confidence: "urgent|0.95"
 * - With explanation: "urgent|0.95|Contains URGENT keyword and mentions production issue"
 *
 * First execution downloads model (~600MB-5GB) and may take 10-30 seconds for loading.
 * Subsequent calls are faster (<5 seconds) as models stay in memory.
 */
@BotCommand
@CommandPkg(
    label = "Classify Text",
    name = "classifyText",
    description = "Categorizes text into predefined categories using on-device Small Language Model",
    node_label = "Classify: {{inputText}}",
    icon = "pkg.svg",
    comment = true,
    text_color = "#2C3E50",
    background_color = "#E8F5E9",
    allowed_agent_targets = {
        AllowedTarget.WINDOWS,
        AllowedTarget.MAC_OS
    },
    return_type = STRING,
    return_required = true
)
public class ClassifyText {

    private static final Logger logger = LogManager.getLogger(ClassifyText.class);

    /**
     * Execute text classification using a Small Language Model
     *
     * @param inputText The text to classify
     * @param categories Comma-separated list of categories (e.g., "urgent, normal, low_priority")
     * @param modelName The model to use (qwen2.5-3b, llama3.2-3b, phi3.5-mini, gemma-2b)
     * @param includeConfidence Whether to include confidence score in output
     * @param includeExplanation Whether to include explanation of why this category was chosen
     * @param timeoutSeconds Maximum time to wait for processing (default: 30)
     * @return Classification result as string
     */
    @Execute
    public Value<String> execute(

        @Idx(index = "1", type = AttributeType.TEXTAREA)
        @Pkg(label = "Input Text", description = "Text to classify")
        @NotEmpty
        String inputText,

        @Idx(index = "2", type = AttributeType.TEXT)
        @Pkg(label = "Categories", description = "Comma-separated list of categories (e.g., 'urgent, normal, spam')")
        @NotEmpty
        String categories,

        @Idx(index = "3", type = AttributeType.SELECT, options = {
            @Idx.Option(index = "3.1", pkg = @Pkg(label = "Qwen2.5 3B (Q4, ~2.1GB, 128K context)", value = "qwen2.5-3b")),
            @Idx.Option(index = "3.2", pkg = @Pkg(label = "Llama 3.2 3B (Q4, ~2.0GB)", value = "llama3.2-3b")),
            @Idx.Option(index = "3.3", pkg = @Pkg(label = "Phi-3.5 Mini (Q4, ~2.23GB, 128K context)", value = "phi3.5-mini")),
            @Idx.Option(index = "3.4", pkg = @Pkg(label = "Gemma 2B (Q4, ~1.7GB)", value = "gemma-2b")),
            @Idx.Option(index = "3.5", pkg = @Pkg(label = "Gemma 4 E2B (Q4, ~3.1GB, 128K context)", value = "gemma4-e2b")),
            @Idx.Option(index = "3.6", pkg = @Pkg(label = "DeepSeek R1 1.5B (Q4, ~1.1GB, reasoning)", value = "deepseek-r1-1.5b"))
        })
        @Pkg(label = "Model", description = "Small Language Model to use for classification (curated top models under 3GB).", default_value = "qwen2.5-3b", default_value_type = DataType.STRING)
        @NotEmpty
        String modelName,

        @Idx(index = "4", type = AttributeType.CHECKBOX)
        @Pkg(label = "Include Confidence Score", description = "Include confidence score in output (0.0-1.0)", default_value = "false", default_value_type = DataType.BOOLEAN)
        Boolean includeConfidence,

        @Idx(index = "5", type = AttributeType.CHECKBOX)
        @Pkg(label = "Include Explanation", description = "Include brief explanation of why this category was chosen", default_value = "false", default_value_type = DataType.BOOLEAN)
        Boolean includeExplanation,

        @Idx(index = "6", type = AttributeType.NUMBER)
        @Pkg(label = "Timeout (seconds)", description = "Maximum time to wait for processing", default_value = "30", default_value_type = DataType.NUMBER)
        @NotEmpty
        Double timeoutSeconds

    ) {

        logger.info("ClassifyText action started - Model: {}, Categories: {}, Timeout: {}s",
                    modelName, categories, timeoutSeconds);

        try {
            // Validate inputs
            if (inputText == null || inputText.trim().isEmpty()) {
                throw new BotCommandException("Input text cannot be empty");
            }

            if (categories == null || categories.trim().isEmpty()) {
                throw new BotCommandException("Categories cannot be empty");
            }

            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 30.0;
            }

            if (includeConfidence == null) {
                includeConfidence = false;
            }

            if (includeExplanation == null) {
                includeExplanation = false;
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

            logger.debug("Using model: {} for text length: {}", modelType.getId(), inputText.length());

            // Initialize inference engine (will load model if needed)
            long startTime = System.currentTimeMillis();
            LlamaInference inference = new LlamaInference(modelType);
            long loadTime = System.currentTimeMillis() - startTime;

            if (loadTime > 1000) {
                logger.info("Model loaded in {}ms (first-time loading takes longer)", loadTime);
            }

            // Build classification prompt
            String prompt = buildClassificationPrompt(inputText, categories, includeConfidence, includeExplanation);
            logger.debug("Generated prompt: {}", prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

            // Generate classification
            String rawResponse = inference.generateText(
                prompt,
                150,  // maxTokens - enough for category + confidence + brief explanation
                0.1f, // Low temperature for consistent classification
                timeoutSeconds.intValue()
            );

            // Parse and format the response
            String result = parseClassificationResponse(rawResponse, categories, includeConfidence, includeExplanation);

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Classification completed in {}ms. Result: {}", totalTime, result);

            return new StringValue(result);

        } catch (Exception e) {
            logger.error("ClassifyText action failed", e);

            // Provide helpful error messages
            String errorMsg;
            if (e.getMessage().contains("timeout")) {
                errorMsg = "Classification timed out after " + timeoutSeconds + " seconds. Try increasing timeout or using a smaller model.";
            } else if (e.getMessage().contains("model") || e.getMessage().contains("GGUF")) {
                errorMsg = "Model loading/inference failed: " + e.getMessage() +
                    ". Ensure sufficient memory (~8GB RAM) and model is downloaded.";
            } else {
                errorMsg = "Classification failed: " + e.getMessage();
            }

            logger.error("Error details: {}", errorMsg);
            throw new BotCommandException(errorMsg, e);
        }
    }

    /**
     * Build the classification prompt based on requested output format
     */
    private String buildClassificationPrompt(String inputText, String categories,
                                            boolean includeConfidence, boolean includeExplanation) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Classify the following text into ONE of these categories: ");
        prompt.append(categories);
        prompt.append("\n\n");

        prompt.append("Text to classify: ");
        prompt.append(inputText);
        prompt.append("\n\n");

        prompt.append("Respond with ONLY the category");

        if (includeConfidence) {
            prompt.append(", followed by a pipe symbol and confidence score (0.0-1.0)");
        }

        if (includeExplanation) {
            prompt.append(", followed by a pipe symbol and a brief explanation");
        }

        prompt.append(".\n\n");

        // Example format based on options
        if (includeConfidence && includeExplanation) {
            prompt.append("Example format: category|0.95|brief explanation\n");
        } else if (includeConfidence) {
            prompt.append("Example format: category|0.95\n");
        } else if (includeExplanation) {
            prompt.append("Example format: category|brief explanation\n");
        } else {
            prompt.append("Example format: category\n");
        }

        prompt.append("Classification:");

        return prompt.toString();
    }

    /**
     * Parse the model's classification response and format it
     */
    private String parseClassificationResponse(String rawResponse, String categories,
                                               boolean includeConfidence, boolean includeExplanation) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            logger.warn("Empty response from model, returning first category as fallback");
            return categories.split(",")[0].trim();
        }

        String cleaned = rawResponse.trim();

        // Strip DeepSeek R1 thinking blocks (<think>...</think>)
        cleaned = LlamaInference.stripThinkingBlocks(cleaned);

        // Remove common prefixes the model might add
        cleaned = cleaned.replaceAll("^(Classification:|Category:|Answer:|Result:)\\s*", "");

        // Take only the first line (model might ramble)
        if (cleaned.contains("\n")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("\n")).trim();
        }

        // If response contains pipe delimiters, it's already formatted correctly
        if (cleaned.contains("|")) {
            return cleaned;
        }

        // Otherwise, validate the category and return it
        String[] categoryList = categories.split(",");
        String lowerCleaned = cleaned.toLowerCase();

        // Check if response matches any category (case-insensitive)
        for (String category : categoryList) {
            if (lowerCleaned.contains(category.trim().toLowerCase())) {
                return category.trim();
            }
        }

        // If no match found, return the cleaned response anyway (might be a variation)
        logger.warn("Response '{}' doesn't exactly match provided categories, returning as-is", cleaned);
        return cleaned;
    }
}
