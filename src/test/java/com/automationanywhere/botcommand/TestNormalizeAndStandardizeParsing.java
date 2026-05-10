package com.automationanywhere.botcommand;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for NormalizeAndStandardize.parseNormalizationResponse().
 * These tests exercise the parsing logic directly — no model download required.
 */
public class TestNormalizeAndStandardizeParsing {

    private NormalizeAndStandardize action;

    @BeforeClass
    public void setUp() {
        action = new NormalizeAndStandardize();
    }

    // ── Empty / null responses ─────────────────────────────────────────────────

    @Test
    public void testNullResponse_preserveOriginal_returnsOriginal() {
        String result = action.parseNormalizationResponse(null, "5551234567", true);
        assertEquals(result, "5551234567");
    }

    @Test
    public void testNullResponse_noPreserve_returnsEmpty() {
        String result = action.parseNormalizationResponse(null, "5551234567", false);
        assertEquals(result, "");
    }

    @Test
    public void testEmptyResponse_preserveOriginal_returnsOriginal() {
        String result = action.parseNormalizationResponse("", "(555) 123-4567", true);
        assertEquals(result, "(555) 123-4567");
    }

    @Test
    public void testBlankResponse_preserveOriginal_returnsOriginal() {
        String result = action.parseNormalizationResponse("   ", "test", true);
        assertEquals(result, "test");
    }

    // ── Clean single-line responses ────────────────────────────────────────────

    @Test
    public void testCleanNormalizedValue_returned() {
        String result = action.parseNormalizationResponse("5551234567", "(555) 123-4567", true);
        assertEquals(result, "5551234567");
    }

    @Test
    public void testNormalizedDate_returned() {
        String result = action.parseNormalizationResponse("2024-03-15", "March 15, 2024", true);
        assertEquals(result, "2024-03-15");
    }

    @Test
    public void testNormalizedName_returned() {
        String result = action.parseNormalizationResponse("John Q. Smith", "SMITH, JOHN Q.", true);
        assertEquals(result, "John Q. Smith");
    }

    // ── Common prefix stripping ────────────────────────────────────────────────

    @Test
    public void testOutputPrefixStripped() {
        String result = action.parseNormalizationResponse("Output: john.doe@example.com", "John.Doe@EXAMPLE.COM", true);
        assertEquals(result, "john.doe@example.com");
    }

    @Test
    public void testResultPrefixStripped() {
        String result = action.parseNormalizationResponse("Result: 2024-03-15", "03/15/2024", true);
        assertEquals(result, "2024-03-15");
    }

    @Test
    public void testNormalizedPrefixStripped() {
        String result = action.parseNormalizationResponse("Normalized: 5551234567", "(555) 123-4567", true);
        assertEquals(result, "5551234567");
    }

    // ── Multi-line responses — uses first non-empty line ─────────────────────

    @Test
    public void testMultiLine_firstLineUsed() {
        String result = action.parseNormalizationResponse(
            "5551234567\nNote: removed all formatting", "(555) 123-4567", true);
        assertEquals(result, "5551234567");
    }

    @Test
    public void testMultiLine_withCommentary() {
        String result = action.parseNormalizationResponse(
            "2024-03-15\nThis is in ISO 8601 format.", "March 15, 2024", true);
        assertEquals(result, "2024-03-15");
    }

    // ── Longer-than-original output is now accepted (2x heuristic removed) ────

    @Test
    public void testLongerThanOriginalIsReturned() {
        // "1 St." normalized to "1 Street" is longer but valid
        String original = "1 St.";
        String normalized = "1 Street, Suite 100, New York, NY 10001";
        String result = action.parseNormalizationResponse(normalized, original, true);
        // Should return the normalized value, not fall back to original
        assertEquals(result, normalized);
    }

    @Test
    public void testAbbreviationExpansionAllowed() {
        // "Dr" → "Doctor" or abbreviation expanded — should not be rejected
        String original = "Dr";
        String expanded = "Doctor";
        String result = action.parseNormalizationResponse(expanded, original, false);
        assertEquals(result, expanded);
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() {
        String response = "<think>The phone number format should be digits only.</think>5551234567";
        String result = action.parseNormalizationResponse(response, "(555) 123-4567", true);
        assertEquals(result, "5551234567");
    }

    @Test
    public void testUnclosedThinkingBlockStripped() {
        String response = "5551234567<think>Still processing";
        String result = action.parseNormalizationResponse(response, "(555) 123-4567", true);
        assertEquals(result, "5551234567");
    }

    // ── Model token limits ────────────────────────────────────────────────────

    @Test
    public void testTokenLimitIncreasedFromOriginal() {
        // MAX_OUTPUT_TOKENS should be > 100 (the old hardcoded value) to support address normalization
        assertTrue(NormalizeAndStandardize.MAX_OUTPUT_TOKENS > 100,
            "MAX_OUTPUT_TOKENS should be > 100 to handle address and custom normalization");
    }

    @Test
    public void testAllModelsHavePositiveOutputTokenLimit() {
        String[] models = {"qwen2.5-3b", "llama3.2-3b", "phi3.5-mini", "gemma-2b", "gemma4-e2b", "deepseek-r1-1.5b"};
        for (String modelId : models) {
            com.automationanywhere.botcommand.utils.ModelManager.ModelType type =
                com.automationanywhere.botcommand.utils.ModelManager.ModelType.fromId(modelId);
            int effective = Math.min(NormalizeAndStandardize.MAX_OUTPUT_TOKENS, type.getMaxOutputTokens());
            assertTrue(effective > 0, "Effective token limit should be positive for " + modelId);
            assertTrue(effective <= type.getMaxOutputTokens(),
                "Effective token limit must not exceed model max for " + modelId);
        }
    }
}
