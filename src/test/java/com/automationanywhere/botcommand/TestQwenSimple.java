package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.utils.LlamaInference;
import com.automationanywhere.botcommand.utils.ModelManager;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaOutput;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Simple direct test of Qwen2.5 model to diagnose generation issues
 */
public class TestQwenSimple {

    @BeforeClass
    public void setUp() {
        System.out.println("=== Direct Qwen2.5 Test ===");
    }

    @AfterClass
    public void tearDown() {
        ModelManager.getInstance().shutdown();
    }

    @Test
    public void testQwenWithChatTemplate() throws Exception {
        System.out.println("\n[TEST] Qwen2.5 with proper chat template");

        ModelManager.ModelType modelType = ModelManager.ModelType.QWEN2_5_3B;
        ModelManager manager = ModelManager.getInstance();

        // Get the model
        System.out.println("Loading model...");
        var model = manager.getModel(modelType);
        System.out.println("Model loaded!");

        // Format prompt with Qwen2.5 chat template
        String prompt = "<|im_start|>system\nYou are a helpful assistant<|im_end|>\n" +
                       "<|im_start|>user\nWhat is the capital of Florida? Answer in one word.<|im_end|>\n" +
                       "<|im_start|>assistant\n";

        System.out.println("Prompt: " + prompt);
        System.out.println("\nStarting generation...");

        InferenceParameters params = new InferenceParameters(prompt)
            .setTemperature(0.3f)
            .setNPredict(50);

        StringBuilder output = new StringBuilder();
        long startTime = System.currentTimeMillis();
        int tokenCount = 0;

        try {
            for (LlamaOutput result : model.generate(params)) {
                String token = result.toString();
                output.append(token);
                System.out.print(token);  // Print each token as it comes
                System.out.flush();
                tokenCount++;

                // Safety: stop after 100 tokens
                if (tokenCount > 100) {
                    System.out.println("\n[Stopping after 100 tokens]");
                    break;
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("\n\nGeneration completed!");
            System.out.println("Time: " + elapsed + "ms");
            System.out.println("Tokens: " + tokenCount);
            System.out.println("Output: " + output.toString());

        } catch (Exception e) {
            System.err.println("\nGeneration failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    public void testQwenWithoutChatTemplate() throws Exception {
        System.out.println("\n[TEST] Qwen2.5 WITHOUT chat template (will likely fail)");

        ModelManager.ModelType modelType = ModelManager.ModelType.QWEN2_5_3B;
        ModelManager manager = ModelManager.getInstance();

        var model = manager.getModel(modelType);

        // Raw prompt without chat template
        String prompt = "Q: What is 2+2? A:";

        System.out.println("Prompt: " + prompt);
        System.out.println("\nStarting generation...");

        InferenceParameters params = new InferenceParameters(prompt)
            .setTemperature(0.3f)
            .setNPredict(20);

        StringBuilder output = new StringBuilder();
        long startTime = System.currentTimeMillis();
        int tokenCount = 0;

        try {
            // Set a manual timeout
            long timeoutMs = 10000; // 10 seconds

            for (LlamaOutput result : model.generate(params)) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    System.out.println("\n[Manual timeout after 10 seconds - no tokens generated]");
                    break;
                }

                String token = result.toString();
                output.append(token);
                System.out.print(token);
                System.out.flush();
                tokenCount++;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("\n\nResult:");
            System.out.println("Time: " + elapsed + "ms");
            System.out.println("Tokens generated: " + tokenCount);
            System.out.println("Output: " + output.toString());

            if (tokenCount == 0) {
                System.out.println("\n✗ As expected: No tokens generated without proper chat template!");
            }

        } catch (Exception e) {
            System.err.println("\nGeneration issue: " + e.getMessage());
        }
    }
}
