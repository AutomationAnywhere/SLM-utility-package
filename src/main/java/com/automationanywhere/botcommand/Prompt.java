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
 * Prompt Action
 *
 * Send a custom prompt to a Small Language Model and receive generated text.
 * This is a general-purpose action for any text generation task:
 * - Question answering
 * - Text summarization
 * - Data extraction
 * - Format conversion
 * - Code generation
 * - Creative writing
 *
 * The prompt should include the full instruction/question for the model.
 * For best results, use clear, specific prompts.
 *
 * Example prompts:
 * - "Q: What is the capital of France? A:"
 * - "Summarize the following text in 2 sentences: [text]"
 * - "Extract the email address from this text: [text]"
 * - "Translate to Spanish: Hello, how are you?"
 *
 * First execution will load the model (~5-30 seconds).
 * Subsequent calls are faster (<5 seconds) as model stays in memory.
 */
@BotCommand
@CommandPkg(
    label = "Prompt Model",
    name = "promptModel",
    description = "Send a custom prompt to a Small Language Model for text generation",
    node_label = "Prompt: {{modelName}}",
    icon = "pkg.svg",
    allowed_agent_targets = {
//                AllowedTarget.HEADLESS,
            AllowedTarget.WINDOWS,
            AllowedTarget.MAC_OS
//                AllowedTarget.ONDEMAND_CLOUD
    },
    return_type = STRING,
    return_required = true,
    comment = true
)
public class Prompt {

    private static final Logger logger = LogManager.getLogger(Prompt.class);

    /**
     * Execute prompt generation with a Small Language Model
     *
     * @param promptText The prompt/instruction to send to the model
     * @param modelName The model to use for generation
     * @param timeoutSeconds Maximum time to wait for generation (default: 30)
     * @param temperature Controls randomness (0.0-1.0). Low=focused, High=creative (default: 0.3)
     * @return Generated text response from the model
     */
    @Execute
    public Value<String> execute(

        @Idx(index = "1", type = AttributeType.TEXTAREA)
        @Pkg(label = "Prompt", description = "The prompt or instruction to send to the model")
        @NotEmpty
        String promptText,

        @Idx(index = "2", type = AttributeType.SELECT, options = {
            @Idx.Option(index = "2.1", pkg = @Pkg(label = "Qwen2.5 3B (Q4, ~2.1GB, 128K context)", value = "qwen2.5-3b")),
            @Idx.Option(index = "2.2", pkg = @Pkg(label = "Llama 3.2 3B (Q4, ~2.0GB)", value = "llama3.2-3b")),
            @Idx.Option(index = "2.3", pkg = @Pkg(label = "Phi-3.5 Mini (Q4, ~2.23GB, 128K context)", value = "phi3.5-mini")),
            @Idx.Option(index = "2.4", pkg = @Pkg(label = "Gemma 2B (Q4, ~1.7GB)", value = "gemma-2b")),
            @Idx.Option(index = "2.5", pkg = @Pkg(label = "Gemma 4 E2B (Q4, ~3.1GB, 128K context)", value = "gemma4-e2b")),
            @Idx.Option(index = "2.6", pkg = @Pkg(label = "DeepSeek R1 1.5B (Q4, ~1.1GB, reasoning)", value = "deepseek-r1-1.5b"))
        })
        @Pkg(label = "Model", description = "Small Language Model to use for generation (curated top models under 3GB).", default_value = "qwen2.5-3b", default_value_type = DataType.STRING)
        @NotEmpty
        String modelName,

        @Idx(index = "3", type = AttributeType.NUMBER)
        @Pkg(label = "Timeout (seconds)", description = "Maximum time to wait for generation", default_value = "30", default_value_type = DataType.NUMBER)
        @NotEmpty
        Double timeoutSeconds,

        @Idx(index = "4", type = AttributeType.NUMBER)
        @Pkg(label = "Temperature", description = "Controls randomness in generation. Range: 0.0-1.0. Low (0.0-0.3) = focused, accurate, deterministic responses. Medium (0.4-0.7) = balanced creativity. High (0.8-1.0) = creative, diverse, but less predictable. Recommended: 0.3 for factual tasks, 0.7 for creative writing.", default_value = "0.3", default_value_type = DataType.NUMBER)
        @NotEmpty
        Double temperature

    ) {

        logger.info("Prompt action started - Model: {}, Timeout: {}s, Temperature: {}, Prompt length: {}",
            modelName, timeoutSeconds, temperature, promptText.length());

        try {
            // Validate inputs
            if (promptText == null || promptText.trim().isEmpty()) {
                throw new BotCommandException("Prompt text cannot be empty");
            }

            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                timeoutSeconds = 30.0;
            }

            // Validate and clamp temperature
            if (temperature == null) {
                temperature = 0.3;
            }
            if (temperature < 0.0) {
                logger.warn("Temperature {} is below minimum 0.0, clamping to 0.0", temperature);
                temperature = 0.0;
            }
            if (temperature > 1.0) {
                logger.warn("Temperature {} is above maximum 1.0, clamping to 1.0", temperature);
                temperature = 1.0;
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

            logger.debug("Using model: {} for prompt: {}",
                modelType.getId(),
                promptText.length() > 100 ? promptText.substring(0, 100) + "..." : promptText);

            // Initialize inference engine (will load model if needed)
            long startTime = System.currentTimeMillis();
            LlamaInference inference = new LlamaInference(modelType);
            long loadTime = System.currentTimeMillis() - startTime;

            if (loadTime > 1000) {
                logger.info("Model loaded in {}ms (first-time loading takes longer)", loadTime);
            }

            // Use model-specific max tokens (each model has different context windows)
            int maxTokens = modelType.getMaxOutputTokens();
            logger.debug("Using max tokens: {} for model: {}", maxTokens, modelType.getId());

            // Generate response using general-purpose text generation
            String response = inference.generateText(
                promptText,
                maxTokens,
                temperature.floatValue(),
                timeoutSeconds.intValue()
            );

            // Clean up the response
            String cleaned = cleanResponse(response);

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Generation completed in {}ms. Response length: {} chars",
                totalTime, cleaned.length());

            return new StringValue(cleaned);

        } catch (Exception e) {
            logger.error("Prompt action failed", e);

            // Provide helpful error messages
            String errorMsg;
            if (e.getMessage().contains("timeout")) {
                errorMsg = "Generation timed out after " + timeoutSeconds + " seconds. " +
                    "Try increasing timeout or using a smaller model.";
            } else if (e.getMessage().contains("model") || e.getMessage().contains("GGUF")) {
                errorMsg = "Model loading/inference failed: " + e.getMessage() +
                    ". Ensure model is downloaded (use ValidateDevice action) and sufficient memory (~8GB RAM) is available.";
            } else {
                errorMsg = "Text generation failed: " + e.getMessage();
            }

            logger.error("Error details: {}", errorMsg);
            throw new BotCommandException(errorMsg, e);
        }
        // Note: Models stay loaded in ModelManager for reuse across calls
    }

    /**
     * Clean up model response by removing common artifacts
     * - Removes markdown code block markers (```json, ```, etc.) anywhere in text
     * - Trims whitespace
     * - Stops at common question markers (Q:, Answer:, Based on, etc.)
     */
    private String cleanResponse(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String cleaned = response.trim();

        // Strip DeepSeek R1 thinking blocks (<think>...</think>)
        cleaned = LlamaInference.stripThinkingBlocks(cleaned);

        // Remove ALL markdown code block markers (anywhere in the text, not just at start/end)
        // This handles cases where the model generates: "Here's the JSON: ```json\n{...}\n```"
        cleaned = cleaned.replaceAll("```json\\s*", "");       // Remove all ```json markers
        cleaned = cleaned.replaceAll("```\\s*", "");           // Remove all ``` markers

        cleaned = cleaned.trim();

        // Stop at common continuation patterns (to avoid rambling)
        String[] stopPatterns = {
            " B:",              // Multiple choice continuation (A:, B:, C:, etc.)
            "\n\n2.",           // Numbered continuation (2., 3., etc.)
            "\n\nQ:",           // Next question
            "\n\nAnswer:",      // Next answer
            "\n\nBased on",     // Commentary
            "\n\n\n",           // Multiple newlines (often indicates end of answer)
        };

        for (String pattern : stopPatterns) {
            int stopIndex = cleaned.indexOf(pattern);
            if (stopIndex > 0) {
                cleaned = cleaned.substring(0, stopIndex);
                logger.debug("Stopped response at pattern: {}", pattern);
                break;
            }
        }

        return cleaned.trim();
    }
}
