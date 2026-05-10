package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utils.ModelManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.*;

/**
 * Tests for ValidateDevice action.
 * Tests device validation and model download functionality.
 */
public class TestValidateDevice {

    private ValidateDevice validateAction;

    @BeforeClass
    public void setUp() {
        validateAction = new ValidateDevice();
        System.out.println("=== ValidateDevice Test Suite ===");
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Architecture: " + System.getProperty("os.arch"));
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("=================================");
    }

    @AfterClass
    public void tearDown() {
        ModelManager.getInstance().shutdown();
        System.out.println("Test suite completed");
    }

    /**
     * Test model path validation
     */
    @Test
    public void testModelPathValidation() {
        System.out.println("\n[TEST] Model path validation");

        ModelManager manager = ModelManager.getInstance();

        // Check all model paths are valid
        for (ModelManager.ModelType type : ModelManager.ModelType.values()) {
            Path modelPath = manager.getModelPath(type);
            Path modelDir = manager.getModelDirectory(type);

            System.out.println("Model: " + type.getId());
            System.out.println("  Path: " + modelPath);
            System.out.println("  Dir: " + modelDir);

            assertNotNull(modelPath, "Model path should not be null");
            assertNotNull(modelDir, "Model directory should not be null");
            assertTrue(modelPath.isAbsolute(), "Model path should be absolute");
            assertTrue(modelDir.isAbsolute(), "Model directory should be absolute");
        }
    }

    /**
     * Test ValidateDevice with invalid model name
     */
    @Test
    public void testInvalidModelName() {
        System.out.println("\n[TEST] Invalid model name");

        try {
            validateAction.execute("invalid-model");
            fail("Should throw exception for invalid model name");

        } catch (Exception e) {
            System.out.println("Expected error: " + e.getMessage());
            assertTrue(e.getMessage().contains("Invalid model name"),
                "Error should mention invalid model name");
        }
    }

    /**
     * Test ValidateDevice checks model existence
     */
    @Test
    public void testModelExistenceCheck() {
        System.out.println("\n[TEST] Model existence check");

        ModelManager manager = ModelManager.getInstance();

        // Check Qwen2.5-3B
        Path tinyLlamaPath = manager.getModelPath(ModelManager.ModelType.QWEN2_5_3B);
        boolean exists = Files.exists(tinyLlamaPath);

        System.out.println("Qwen2.5-3B path: " + tinyLlamaPath);
        System.out.println("Exists: " + exists);

        if (exists) {
            try {
                long size = Files.size(tinyLlamaPath);
                System.out.println("Size: " + (size / 1024 / 1024) + "MB");
                assertTrue(size > 100 * 1024 * 1024, "Model file should be > 100MB");

            } catch (Exception e) {
                fail("Should be able to check model size: " + e.getMessage());
            }
        } else {
            System.out.println("Model not downloaded yet (expected for first run)");
        }
    }

    /**
     * Full validation test (disabled by default as it may download models)
     * Enable to test full download functionality
     */
    @Test(enabled = false)
    public void testFullValidationWithDownload() {
        System.out.println("\n[TEST] Full validation with download");
        System.out.println("This test may download Qwen2.5-3B (~669MB)");

        try {
            long startTime = System.currentTimeMillis();

            DictionaryValue result = validateAction.execute("qwen2.5-3b");

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n✓ Validation completed in " + elapsed + "ms");
            System.out.println("Device ready: " + ((StringValue) result.get("supported")).get());

            assertNotNull(result, "Result should not be null");
            assertTrue("true".equals(((StringValue) result.get("supported")).get()), "Device should be ready after validation");

            // Verify model file exists
            ModelManager manager = ModelManager.getInstance();
            Path modelPath = manager.getModelPath(ModelManager.ModelType.QWEN2_5_3B);
            assertTrue(Files.exists(modelPath), "Model file should exist after validation");

            // Verify file size
            long size = Files.size(modelPath);
            System.out.println("Model size: " + (size / 1024 / 1024) + "MB");
            assertTrue(size > 500 * 1024 * 1024, "Model should be at least 500MB");

        } catch (Exception e) {
            System.out.println("\n✗ Test failed: " + e.getMessage());
            fail("Validation should not fail: " + e.getMessage());
        }
    }

    /**
     * Test validation of already downloaded model
     */
    @Test(enabled = false)
    public void testValidationOfExistingModel() {
        System.out.println("\n[TEST] Validation of existing model");
        System.out.println("This test requires Qwen2.5-3B to be already downloaded");

        try {
            ModelManager manager = ModelManager.getInstance();
            Path modelPath = manager.getModelPath(ModelManager.ModelType.QWEN2_5_3B);

            if (!Files.exists(modelPath)) {
                System.out.println("Model not found, skipping test");
                return;
            }

            long startTime = System.currentTimeMillis();

            DictionaryValue result = validateAction.execute("qwen2.5-3b");

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n✓ Validation completed in " + elapsed + "ms");
            System.out.println("Device ready: " + ((StringValue) result.get("supported")).get());

            assertTrue("true".equals(((StringValue) result.get("supported")).get()), "Device should be ready");
            assertTrue(elapsed < 5000, "Should be fast for existing model (< 5 seconds)");

        } catch (Exception e) {
            System.out.println("\n✗ Test failed: " + e.getMessage());
            fail("Validation should not fail: " + e.getMessage());
        }
    }

    /**
     * Test all model types are supported
     */
    @Test
    public void testAllModelTypesSupported() {
        System.out.println("\n[TEST] All model types supported");

        String[] models = {"qwen2.5-3b", "llama3.2-3b", "phi3.5-mini", "gemma-2b", "gemma4-e2b", "deepseek-r1-1.5b"};

        for (String model : models) {
            System.out.println("Testing model: " + model);

            try {
                ModelManager.ModelType type = ModelManager.ModelType.fromId(model);
                assertNotNull(type, "Model type should be resolvable: " + model);
                System.out.println("  ✓ " + model + " is supported");

            } catch (Exception e) {
                fail("Model should be supported: " + model + ", error: " + e.getMessage());
            }
        }
    }
}
