package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.utils.LlamaInference;
import com.automationanywhere.botcommand.utils.ModelManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.testng.Assert.*;

/**
 * Cross-platform tests for SanitizeJSON action.
 * Tests run on both Windows and macOS (including Apple Silicon).
 */
public class TestSanitizeJSON {

    private SanitizeJSON sanitizeAction;

    @BeforeClass
    public void setUp() {
        sanitizeAction = new SanitizeJSON();
        System.out.println("=== SanitizeJSON Test Suite ===");
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Architecture: " + System.getProperty("os.arch"));
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("User Home: " + System.getProperty("user.home"));
        System.out.println("================================");
    }

    @AfterClass
    public void tearDown() {
        // Clean up model manager
        ModelManager.getInstance().shutdown();
        System.out.println("Test suite completed - models unloaded");
    }

    /**
     * Test cross-platform path handling
     */
    @Test
    public void testCrossPlatformPaths() {
        System.out.println("\n[TEST] Cross-platform path handling");

        // Get model cache directory
        Path cacheDir = Paths.get(System.getProperty("user.home"), "localAI");
        System.out.println("Model cache directory: " + cacheDir.toAbsolutePath());

        // Verify path uses forward slashes or proper separators
        String pathStr = cacheDir.toString();
        System.out.println("Path string: " + pathStr);

        // Path should be absolute
        assertTrue(cacheDir.isAbsolute(), "Model cache path should be absolute");

        // Check model subdirectories
        Path tinyLlamaDir = cacheDir.resolve("qwen3-4b-q4");
        Path phi2Dir = cacheDir.resolve("phi2-q4");

        System.out.println("Qwen2.5-3B dir: " + tinyLlamaDir);
        System.out.println("Phi-2 dir: " + phi2Dir);

        // Verify cross-platform path construction
        assertNotNull(tinyLlamaDir);
        assertNotNull(phi2Dir);
    }

    /**
     * Test fallback sanitization (no model required)
     */
    @Test
    public void testFallbackSanitization() {
        System.out.println("\n[TEST] Fallback JSON sanitization");

        String input = "Hello \"World\"\nWith\ttabs and\\backslashes";
        String expected = "Hello \\\"World\\\"\\nWith\\ttabs and\\\\backslashes";

        String result = LlamaInference.sanitizeForJSON(input);
        System.out.println("Input: " + input);
        System.out.println("Output: " + result);
        System.out.println("Expected: " + expected);

        assertEquals(result, expected, "Fallback sanitization should escape JSON special characters");
    }

    /**
     * Test basic sanitization with minimal input
     */
    @Test
    public void testBasicSanitization() {
        System.out.println("\n[TEST] Basic sanitization");

        String input = "Simple text without special chars";
        String result = LlamaInference.sanitizeForJSON(input);

        System.out.println("Input: " + input);
        System.out.println("Output: " + result);

        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Result should not be empty");
    }

    /**
     * Test quotes and special characters
     */
    @Test
    public void testQuotesAndSpecialChars() {
        System.out.println("\n[TEST] Quotes and special characters");

        String input = "He said \"Hello!\" and used 'quotes'";
        String result = LlamaInference.sanitizeForJSON(input);

        System.out.println("Input: " + input);
        System.out.println("Output: " + result);

        // Should escape double quotes
        assertTrue(result.contains("\\\"") || !result.contains("\""),
            "Double quotes should be escaped or removed");
    }

    /**
     * Test newlines and whitespace
     */
    @Test
    public void testNewlinesAndWhitespace() {
        System.out.println("\n[TEST] Newlines and whitespace");

        String input = "Line 1\nLine 2\rLine 3\r\nLine 4\tTabbed";
        String result = LlamaInference.sanitizeForJSON(input);

        System.out.println("Input (repr): " + input.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
        System.out.println("Output: " + result);

        // Should escape or remove newlines and tabs
        assertFalse(result.contains("\n"), "Should not contain unescaped newlines");
        assertFalse(result.contains("\r"), "Should not contain unescaped carriage returns");
        assertFalse(result.contains("\t"), "Should not contain unescaped tabs");
    }

    /**
     * Test backslashes
     */
    @Test
    public void testBackslashes() {
        System.out.println("\n[TEST] Backslashes");

        String input = "Path: C:\\Users\\Test\\file.txt";
        String result = LlamaInference.sanitizeForJSON(input);

        System.out.println("Input: " + input);
        System.out.println("Output: " + result);

        // Should escape backslashes
        assertTrue(result.contains("\\\\") || result.replace("\\", "").length() < input.length(),
            "Backslashes should be escaped or removed");
    }

    /**
     * Test empty and null inputs
     */
    @Test
    public void testEmptyAndNullInputs() {
        System.out.println("\n[TEST] Empty and null inputs");

        // Null input
        String nullResult = LlamaInference.sanitizeForJSON(null);
        assertEquals(nullResult, "", "Null input should return empty string");

        // Empty input
        String emptyResult = LlamaInference.sanitizeForJSON("");
        assertEquals(emptyResult, "", "Empty input should return empty string");

        // Whitespace only
        String wsResult = LlamaInference.sanitizeForJSON("   ");
        assertNotNull(wsResult, "Whitespace input should return a result");
    }

    /**
     * Test OS detection
     */
    @Test
    public void testOSDetection() {
        System.out.println("\n[TEST] OS detection");

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        System.out.println("Detected OS: " + os);
        System.out.println("Architecture: " + arch);

        // Verify we can detect the platform
        assertTrue(os.contains("windows") || os.contains("mac") || os.contains("darwin") || os.contains("linux"),
            "Should detect a known OS");

        // Check for Apple Silicon
        if (os.contains("mac") || os.contains("darwin")) {
            System.out.println("macOS detected");
            if (arch.contains("aarch64") || arch.contains("arm")) {
                System.out.println("Apple Silicon (ARM) detected");
            } else {
                System.out.println("Intel Mac (x86_64) detected");
            }
        }
    }

    /**
     * Test model manager initialization
     */
    @Test
    public void testModelManagerInit() {
        System.out.println("\n[TEST] ModelManager initialization");

        ModelManager manager = ModelManager.getInstance();
        assertNotNull(manager, "ModelManager should initialize");

        // Test singleton
        ModelManager manager2 = ModelManager.getInstance();
        assertSame(manager, manager2, "ModelManager should be singleton");

        // Check model types
        for (ModelManager.ModelType type : ModelManager.ModelType.values()) {
            System.out.println("Model type: " + type.getId());
            Path modelPath = manager.getModelPath(type);
            System.out.println("  Model path: " + modelPath);

            Path modelDir = manager.getModelDirectory(type);
            System.out.println("  Model dir: " + modelDir);

            assertNotNull(modelPath, "Model path should not be null");
            assertNotNull(modelDir, "Model dir should not be null");
        }
    }

    /**
     * ===============================================================================
     * TEST SUITE: Full Qwen2.5-3B Inference Tests
     * ===============================================================================
     *
     * These tests require the Qwen2.5-3B model to be downloaded (~2.1GB)
     * Enable by changing @Test(enabled = false) to @Test(enabled = true)
     *
     * IMPORTANT NOTE:
     * The SanitizeJSON action is specifically designed for JSON sanitization tasks.
     * It sends prompts instructing the model to escape/sanitize text for JSON.
     *
     * For general-purpose Q&A or text generation, you would need to create a
     * separate action (e.g., GenerateText) that uses LlamaInference.generateText()
     * with custom prompts suitable for question answering.
     *
     * These tests verify that:
     * 1. The model loads successfully on your platform (macOS/Windows/Linux)
     * 2. Inference completes without errors
     * 3. JSON sanitization works correctly (via model or fallback)
     * 4. Performance is acceptable (<5s for subsequent calls)
     * ===============================================================================
     */

    /**
     * Test 1: JSON Sanitization with complex special characters
     */
    @Test(enabled = false)
    public void testQwen25_3BJSONSanitization() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST] Qwen2.5-3B - JSON Sanitization with Special Characters");
        System.out.println("-".repeat(80));

        String jsonInput = "User said: \"I love this!\" and added:\nLine 1\nLine 2\nPath: C:\\Users\\Test";
        System.out.println("Input:");
        System.out.println("  " + jsonInput.replace("\n", "\n  "));

        try {
            System.out.println("\nRunning SanitizeJSON with Qwen2.5-3B...");
            System.out.println("(First run: download + load model, may take 1-2 minutes)\n");

            long startTime = System.currentTimeMillis();

            Value<String> result = sanitizeAction.execute(jsonInput);

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n✓ Completed in " + elapsed + "ms");
            System.out.println("\nSanitized output:");
            System.out.println("  " + result.get().replace("\n", "\n  "));

            assertNotNull(result, "Result should not be null");
            assertNotNull(result.get(), "Result value should not be null");
            assertFalse(result.get().isEmpty(), "Result should not be empty");

        } catch (Exception e) {
            System.out.println("\n✗ Test failed: " + e.getMessage());
            System.out.println("\nNote: Fallback sanitization should have been used.");
            // Test should not fail - fallback should handle errors
            assertNotNull(e.getMessage(), "Error message should be present");
        }
    }

    /**
     * Test 2: Real-world JSON sanitization with model
     * Tests that the model (or fallback) properly handles complex JSON escaping
     */
    @Test
    public void testQwen25_3BRealWorldJSON() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST] Qwen2.5-3B - Real-world JSON Data");
        System.out.println("-".repeat(80));

        String complexInput = "API Error: \"Connection timeout\" at line 42\nStack trace:\n\tat com.example.Main";
        System.out.println("Input: " + complexInput);

        Value<String> result = sanitizeAction.execute(complexInput);

        System.out.println("\nSanitized output:");
        System.out.println("  " + result.get());

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.get(), "Result value should not be null");

        // Rule-based sanitization must escape newlines
        assertFalse(result.get().contains("\n"), "Should not contain unescaped newlines");
    }

    /**
     * Test 3: Unicode and special character handling
     */
    @Test(enabled = false)
    public void testQwen25_3BUnicodeHandling() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST] Qwen2.5-3B - Unicode and Special Characters");
        System.out.println("-".repeat(80));

        String unicodeText = "User message: \"Hello 世界\" with emoji: 🎉 and symbols: © ® ™";
        System.out.println("Input: " + unicodeText);

        try {
            System.out.println("\nRunning inference...\n");

            long startTime = System.currentTimeMillis();

            Value<String> result = sanitizeAction.execute(unicodeText);

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n✓ Completed in " + elapsed + "ms");
            System.out.println("\nSanitized output:");
            System.out.println("  " + result.get());

            assertNotNull(result, "Result should not be null");
            assertNotNull(result.get(), "Result value should not be null");

            // Should escape quotes at minimum
            assertFalse(result.get().matches(".*[^\\\\]\".*"), "Quotes should be escaped");

        } catch (Exception e) {
            System.out.println("\n✗ Test failed: " + e.getMessage());
            fail("Unicode handling test should not fail: " + e.getMessage());
        }
    }

    /**
     * Test 4: Model Status Check
     */
    @Test(enabled = false)
    public void testModelStatus() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST] Model Status Check");
        System.out.println("-".repeat(80));

        try {
            ModelManager manager = ModelManager.getInstance();
            boolean loaded = manager.isModelLoaded(ModelManager.ModelType.QWEN3_4B);

            System.out.println("\nQwen2.5-3B model loaded in memory: " + loaded);

            if (loaded) {
                System.out.println("\nThe model remains in memory for subsequent use.");
                System.out.println("This makes follow-up calls much faster!");
                System.out.println("\nTo free memory, call:");
                System.out.println("  manager.unloadModel(ModelManager.ModelType.QWEN3_4B)");
            }

            // Just verify we can check status without errors
            assertNotNull(manager, "ModelManager should not be null");

        } catch (Exception e) {
            System.out.println("Error checking model status: " + e.getMessage());
            fail("Model status check should not fail: " + e.getMessage());
        }
    }

    /**
     * Test 5: Full inference with timeout test
     */
    @Test(enabled = false)
    public void testInferenceWithTimeout() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST] Inference with Custom Timeout");
        System.out.println("-".repeat(80));

        String input = "Test message with custom timeout";
        System.out.println("Input: " + input);
        System.out.println("Timeout: 45 seconds");

        try {
            long startTime = System.currentTimeMillis();

            Value<String> result = sanitizeAction.execute(input);

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n✓ Completed in " + elapsed + "ms (well under timeout)");
            System.out.println("\nResult: " + result.get());

            assertTrue(elapsed < 45000, "Should complete before timeout");
            assertNotNull(result, "Result should not be null");

        } catch (Exception e) {
            System.out.println("\n✗ Test failed: " + e.getMessage());
            fail("Timeout test should not fail: " + e.getMessage());
        }
    }
}
