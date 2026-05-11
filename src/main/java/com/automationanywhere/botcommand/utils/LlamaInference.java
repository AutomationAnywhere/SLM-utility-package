package com.automationanywhere.botcommand.utils;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;

/**
 * Wrapper around llama.cpp for text generation with small language models.
 * Handles inference with timeout support and fallback sanitization.
 *
 * Much simpler than ONNX - llama.cpp handles all the complexity:
 * - Tokenization
 * - KV cache management
 * - Text generation
 * - Cross-platform native libraries
 */
public class LlamaInference {
    private static final Logger logger = LogManager.getLogger(LlamaInference.class);

    private final LlamaModel model;
    private final ModelManager.ModelType modelType;

    // Generation parameters
    private static final float TEMPERATURE = 0.1f;  // Low temperature for deterministic output
    private static final int MAX_TOKENS = 100;      // Maximum tokens to generate

    public LlamaInference(ModelManager.ModelType modelType) throws Exception {
        this.modelType = modelType;
        ModelManager manager = ModelManager.getInstance();

        // Load the model (will be cached if already loaded)
        this.model = manager.getModel(modelType);

        logger.info("LlamaInference initialized for model: {}", modelType.getId());
    }

    /**
     * Generate text based on a prompt with timeout support
     * @param prompt The input prompt
     * @param timeoutSeconds Maximum time to wait for generation
     * @return Generated text
     * @throws Exception if generation fails or times out
     */
    public String generate(String prompt, int timeoutSeconds) throws Exception {
        logger.debug("Generating text for prompt (timeout: {}s): {}", timeoutSeconds,
                    prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);

        // Create inference parameters
        InferenceParameters inferParams = new InferenceParameters(prompt)
            .setTemperature(TEMPERATURE)
            .setNPredict(MAX_TOKENS);

        // Run inference with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            StringBuilder output = new StringBuilder();

            try {
                // Stream the response
                for (LlamaOutput result : model.generate(inferParams)) {
                    output.append(result);
                }

                long elapsedMs = System.currentTimeMillis() - startTime;
                logger.info("Generation completed in {}ms", elapsedMs);

                return output.toString();

            } catch (Exception e) {
                logger.error("Generation failed", e);
                throw new RuntimeException("Generation failed: " + e.getMessage(), e);
            }
        });

        try {
            // Wait for result with timeout
            return future.get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            future.cancel(true);
            executor.shutdownNow();
            throw new RuntimeException("Generation timed out after " + timeoutSeconds + " seconds");

        } catch (ExecutionException e) {
            throw new Exception("Generation failed", e.getCause());

        } finally {
            executor.shutdown();
        }
    }

    /**
     * Simple text sanitization for JSON (fallback method)
     * This doesn't require any model inference
     */
    public static String sanitizeForJSON(String input) {
        if (input == null) {
            return "";
        }

        return input
            .replace("\\", "\\\\")     // Escape backslashes first
            .replace("\"", "\\\"")     // Escape quotes
            .replace("\n", "\\n")      // Escape newlines
            .replace("\r", "\\r")      // Escape carriage returns
            .replace("\t", "\\t")      // Escape tabs
            .replace("\b", "\\b")      // Escape backspace
            .replace("\f", "\\f");     // Escape form feed
    }

    /**
     * Generate sanitized JSON text using the model
     * Falls back to rule-based sanitization if model fails
     */
    public String generateSanitizedJSON(String inputText, int timeoutSeconds) throws Exception {
        // Build the sanitization prompt
        String prompt = String.format(
            "You are a JSON sanitizer. Remove or escape characters that would break JSON: " +
            "quotes, backslashes, newlines, tabs. Preserve meaning. Input: %s. " +
            "Output only the sanitized text.",
            inputText
        );

        // Apply chat template for models that need it
        String formattedPrompt = applyChatTemplate(prompt);

        // Create inference parameters with updated prompt
        InferenceParameters inferParams = new InferenceParameters(formattedPrompt)
            .setTemperature(TEMPERATURE)
            .setNPredict(MAX_TOKENS);

        try {
            // Try to generate using the model with executor timeout
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() -> {
                StringBuilder output = new StringBuilder();
                for (LlamaOutput result : model.generate(inferParams)) {
                    output.append(result);
                }
                return output.toString();
            });

            String result;
            try {
                result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                throw new RuntimeException("Generation timed out");
            } finally {
                executor.shutdown();
            }

            // Clean up the result (remove prompt echo if present)
            String cleaned = result.trim();

            // If model returned something reasonable, use it
            if (!cleaned.isEmpty() && cleaned.length() < inputText.length() * 3) {
                return cleaned;
            } else {
                logger.warn("Model output suspicious, using fallback");
                return sanitizeForJSON(inputText);
            }

        } catch (Exception e) {
            logger.warn("Model generation failed, using fallback sanitization: {}", e.getMessage());
            // Fallback to rule-based sanitization
            return sanitizeForJSON(inputText);
        }
    }

    /**
     * General purpose text generation for any task
     *
     * This method can be used to create custom actions for:
     * - Question answering: "Q: What's the capital of France? A:"
     * - Text summarization: "Summarize this text: [text]"
     * - Data extraction: "Extract the email address from: [text]"
     * - Code generation: "Write a Python function that: [description]"
     *
     * Example usage for Q&A:
     * <pre>
     * LlamaInference inference = new LlamaInference(ModelManager.ModelType.TINYLLAMA);
     * String prompt = "<|system|>\nYou are a helpful assistant.\n<|user|>\nWhat's the capital of Florida?\n<|assistant|>\n";
     * String answer = inference.generateText(prompt, 50, 0.7f, 30);
     * </pre>
     *
     * @param prompt The full prompt/instruction (will be wrapped with chat template if needed)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = deterministic, 1.0 = creative)
     * @param timeoutSeconds Timeout in seconds
     * @return Generated text
     */
    public String generateText(String prompt, int maxTokens, float temperature, int timeoutSeconds) throws Exception {
        logger.debug("Generating text with custom parameters: maxTokens={}, temperature={}", maxTokens, temperature);

        // Apply chat template formatting if needed (especially for Qwen2.5)
        String formattedPrompt = applyChatTemplate(prompt);

        // Add stop sequences to prevent rambling
        String[] stopSequences = {
            "\nQ:",              // Stop at next question
            "\nAnswer:",         // Stop at next answer marker
            "\n\nQ:",            // Double newline + Q
            "\n\nAnswer:",       // Double newline + Answer
            "\nBased on",        // Stop at commentary
            "<|endoftext|>",     // End of text token
            "<|im_end|>",        // Qwen2.5 / DeepSeek end marker
            "</s>",              // End of sequence token
            "<turn|>",           // Gemma 4 turn end marker
            "<|turn>"            // Gemma 4 next turn start
        };

        InferenceParameters inferParams = new InferenceParameters(formattedPrompt)
            .setTemperature(temperature)
            .setNPredict(maxTokens)
            .setStopStrings(stopSequences);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            StringBuilder output = new StringBuilder();

            try {
                for (LlamaOutput result : model.generate(inferParams)) {
                    output.append(result);
                }

                long elapsedMs = System.currentTimeMillis() - startTime;
                logger.info("Generation completed in {}ms", elapsedMs);

                return output.toString();

            } catch (Exception e) {
                logger.error("Generation failed", e);
                throw new RuntimeException("Generation failed: " + e.getMessage(), e);
            }
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            executor.shutdownNow();
            throw new RuntimeException("Generation timed out after " + timeoutSeconds + " seconds");
        } catch (ExecutionException e) {
            throw new Exception("Generation failed", e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Apply chat template formatting if needed for model-specific formats.
     * Qwen2.5 requires specific chat template with <|im_start|> and <|im_end|> tokens.
     * If the prompt already contains chat template markers, return as-is.
     *
     * @param prompt Raw prompt text
     * @return Formatted prompt with chat template if needed
     */
    private String applyChatTemplate(String prompt) {
        // If prompt already has chat template markers, don't wrap again
        if (prompt.contains("<|im_start|>") || prompt.contains("<|system|>") ||
            prompt.contains("<|user|>") || prompt.contains("<|assistant|>") ||
            prompt.contains("<|turn>") || prompt.contains("<start_of_turn>") ||
            prompt.contains("<|end|>")) {
            logger.debug("Prompt already contains chat template markers, using as-is");
            return prompt;
        }

        if (modelType.getPromptTemplate() == ModelManager.ModelType.PromptTemplate.CHATML) {
            String formatted = "<|im_start|>system\n" +
                "You are a helpful assistant<|im_end|>\n" +
                "<|im_start|>user\n" +
                prompt + "<|im_end|>\n" +
                "<|im_start|>assistant\n";
            logger.debug("Applied ChatML template for model: {}", modelType.getId());
            return formatted;
        }

        if (modelType.getPromptTemplate() == ModelManager.ModelType.PromptTemplate.CHATML_QWEN3) {
            // Qwen3 defaults to thinking mode (produces <think>...</think> blocks).
            // Appending /no_think disables it so we get direct, structured answers.
            String formatted = "<|im_start|>system\n" +
                "You are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\n" +
                prompt + " /no_think<|im_end|>\n" +
                "<|im_start|>assistant\n";
            logger.debug("Applied Qwen3 ChatML (no-think) template for model: {}", modelType.getId());
            return formatted;
        }

        if (modelType.getPromptTemplate() == ModelManager.ModelType.PromptTemplate.GEMMA3) {
            String formatted = "<start_of_turn>user\n" +
                prompt + "<end_of_turn>\n" +
                "<start_of_turn>model\n";
            logger.debug("Applied Gemma 3 template for model: {}", modelType.getId());
            return formatted;
        }

        if (modelType.getPromptTemplate() == ModelManager.ModelType.PromptTemplate.GEMMA4) {
            String formatted = "<|turn>system\n" +
                "You are a helpful assistant<turn|>\n" +
                "<|turn>user\n" +
                prompt + "<turn|>\n" +
                "<|turn>model\n";
            logger.debug("Applied Gemma 4 template for model: {}", modelType.getId());
            return formatted;
        }

        if (modelType.getPromptTemplate() == ModelManager.ModelType.PromptTemplate.PHI4) {
            String formatted = "<|system|>\nYou are a helpful assistant<|end|>\n" +
                "<|user|>\n" +
                prompt + "<|end|>\n" +
                "<|assistant|>\n";
            logger.debug("Applied Phi-4 template for model: {}", modelType.getId());
            return formatted;
        }

        logger.debug("Using raw prompt for model: {}", modelType.getId());
        return prompt;
    }

    /**
     * Strip DeepSeek R1 thinking blocks from output.
     * DeepSeek R1 models produce &lt;think&gt;...&lt;/think&gt; reasoning blocks
     * before the final answer. This method removes them.
     *
     * @param text Raw model output
     * @return Text with thinking blocks removed
     */
    public static String stripThinkingBlocks(String text) {
        if (text == null) return text;
        // Remove <think>...</think> blocks (including multiline)
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // Also handle unclosed <think> block (model still thinking when stopped)
        if (cleaned.contains("<think>")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("<think>")).trim();
        }
        // Handle standalone </think> tag (model may output closing tag without opening)
        cleaned = cleaned.replace("</think>", "").trim();
        return cleaned;
    }

    /**
     * Get the model type being used
     */
    public ModelManager.ModelType getModelType() {
        return modelType;
    }
}
