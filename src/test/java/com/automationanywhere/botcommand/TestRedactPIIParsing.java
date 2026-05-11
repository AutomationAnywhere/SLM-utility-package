package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.utils.ModelManager;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for RedactPII.parseRedactionResponse().
 * No model download required.
 */
public class TestRedactPIIParsing {

    private RedactPII action;

    @BeforeClass
    public void setUp() {
        action = new RedactPII();
    }

    // ── Clean response ────────────────────────────────────────────────────────

    @Test
    public void testCleanRedactedText_returned() {
        String response = "Hello [REDACTED], your order has been placed.";
        String result = action.parseRedactionResponse(response, "Hello John, your order has been placed.", "[REDACTED]");
        assertEquals(result, response);
    }

    @Test
    public void testWhitespaceStripped() {
        String response = "  Contact [REDACTED] for details.  ";
        String result = action.parseRedactionResponse(response, "Contact John Smith for details.", "[REDACTED]");
        assertEquals(result, "Contact [REDACTED] for details.");
    }

    // ── Common prefix stripping ───────────────────────────────────────────────

    @Test
    public void testRedactedTextPrefixStripped() {
        String response = "Redacted text: [REDACTED] called at [REDACTED].";
        String result = action.parseRedactionResponse(response, "John called at 555-1234.", "[REDACTED]");
        assertEquals(result, "[REDACTED] called at [REDACTED].");
    }

    @Test
    public void testOutputPrefixStripped() {
        String response = "Output: Send the file to [REDACTED].";
        String result = action.parseRedactionResponse(response, "Send the file to user@example.com.", "[REDACTED]");
        assertEquals(result, "Send the file to [REDACTED].");
    }

    // ── Null / empty response falls back to original ──────────────────────────

    @Test
    public void testNullResponse_returnsOriginal() {
        String original = "Call John at 555-1234.";
        String result = action.parseRedactionResponse(null, original, "[REDACTED]");
        assertEquals(result, original);
    }

    @Test
    public void testEmptyResponse_returnsOriginal() {
        String original = "Email jane@example.com for info.";
        String result = action.parseRedactionResponse("", original, "[REDACTED]");
        assertEquals(result, original);
    }

    // ── Custom replacement tokens ─────────────────────────────────────────────

    @Test
    public void testCustomToken_stars() {
        String response = "Dear ***, your invoice is ready.";
        String result = action.parseRedactionResponse(response, "Dear John Smith, your invoice is ready.", "***");
        assertEquals(result, response);
    }

    @Test
    public void testCustomToken_piiTag() {
        String response = "Contact <PII> for more information.";
        String result = action.parseRedactionResponse(response, "Contact john@example.com for more information.", "<PII>");
        assertEquals(result, response);
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() {
        String response = "<think>I need to redact the name and email.</think>Dear [REDACTED], please contact [REDACTED].";
        String result = action.parseRedactionResponse(response, "Dear John, please contact john@example.com.", "[REDACTED]");
        assertEquals(result, "Dear [REDACTED], please contact [REDACTED].");
    }

    // ── Temperature constant ──────────────────────────────────────────────────

    @Test
    public void testRedactTemperatureIsLow() {
        assertTrue(RedactPII.REDACT_TEMPERATURE <= 0.2f,
            "Redaction should use low temperature for deterministic output");
    }

    // ── Token budget adapts to input length ───────────────────────────────────

    @Test
    public void testAllModelsHavePositiveTokenBudget() {
        String sampleInput = "This is a sample text with about thirty words total for testing purposes right here okay done.";
        int wordCount = sampleInput.split("\\s+").length;
        int tokenBudget = Math.max(200, wordCount * 2);

        String[] models = {"qwen3-4b", "llama3.2-3b", "phi4-mini", "gemma3-4b", "gemma4-e2b", "deepseek-r1-1.5b"};
        for (String modelId : models) {
            ModelManager.ModelType type = ModelManager.ModelType.fromId(modelId);
            int effective = Math.min(tokenBudget, type.getMaxOutputTokens());
            assertTrue(effective > 0, "Effective token budget should be positive for " + modelId);
        }
    }
}
