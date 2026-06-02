package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.utils.LlamaInference;
import com.automationanywhere.botcommand.utils.LlamaServerManager;
import com.automationanywhere.botcommand.utils.ModelManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Direct test of Qwen3 model via the llama-server subprocess approach.
 * Requires the model to be downloaded and llama-server binary to be installed.
 */
public class TestQwenSimple {

    @BeforeClass
    public void setUp() {
        System.out.println("=== Direct Qwen3 4B Test (via llama-server) ===");
    }

    @AfterClass
    public void tearDown() {
        ModelManager.getInstance().shutdown();
    }

    @Test
    public void testQwenWithChatTemplate() throws Exception {
        System.out.println("\n[TEST] Qwen3 4B with chat template via LlamaInference");

        ModelManager.ModelType modelType = ModelManager.ModelType.QWEN3_4B;

        System.out.println("Loading model via llama-server...");
        long startTime = System.currentTimeMillis();

        LlamaInference inference = new LlamaInference(modelType);

        long loadTime = System.currentTimeMillis() - startTime;
        System.out.println("Model loaded in " + loadTime + "ms");

        String prompt = "What is the capital of Florida? Answer in one word.";
        System.out.println("Prompt: " + prompt);

        String result = inference.generateText(prompt, 50, 0.3f, 60);
        System.out.println("Output: " + result);

        assertNotNull(result);
        assertFalse(result.trim().isEmpty(), "Result should not be empty");
        System.out.println("OK: Qwen3 4B generated output successfully");
    }

    @Test
    public void testQwenWithoutChatTemplate() throws Exception {
        System.out.println("\n[TEST] Qwen3 4B raw generate via LlamaInference");

        ModelManager.ModelType modelType = ModelManager.ModelType.QWEN3_4B;
        LlamaInference inference = new LlamaInference(modelType);

        String prompt = "Q: What is 2+2? A:";
        System.out.println("Prompt: " + prompt);

        String result = inference.generate(prompt, 30);
        System.out.println("Output: " + result);

        // Raw prompt may or may not produce good output, but should not throw
        assertNotNull(result);
        System.out.println("OK: generate() completed without error");
    }
}
