package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;

import static org.testng.Assert.*;

/**
 * Unit tests for ClassifyText.parseClassificationResponse().
 * These tests exercise the parsing logic directly — no model download required.
 */
public class TestClassifyTextParsing {

    private ClassifyText action;

    @BeforeClass
    public void setUp() {
        action = new ClassifyText();
    }

    private String cat(LinkedHashMap<String, Value<?>> r) {
        return ((StringValue) r.get("category")).get();
    }

    private String conf(LinkedHashMap<String, Value<?>> r) {
        Value<?> v = r.get("confidence");
        return v == null ? null : ((StringValue) v).get();
    }

    private String expl(LinkedHashMap<String, Value<?>> r) {
        Value<?> v = r.get("explanation");
        return v == null ? null : ((StringValue) v).get();
    }

    // ── Empty / null responses throw ──────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testNullResponse_throws() {
        action.parseClassificationResponse(null, "urgent, normal, low", false, false);
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testEmptyResponse_throws() {
        action.parseClassificationResponse("", "urgent, normal, low", false, false);
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testBlankResponse_throws() {
        action.parseClassificationResponse("   ", "urgent, normal, low", false, false);
    }

    // ── Clean single-category match ────────────────────────────────────────────

    @Test
    public void testExactCategoryMatch() {
        var r = action.parseClassificationResponse("urgent", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    @Test
    public void testCaseInsensitiveMatch() {
        var r = action.parseClassificationResponse("URGENT", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    @Test
    public void testCategoryWithTrailingPeriod() {
        var r = action.parseClassificationResponse("urgent.", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    @Test
    public void testCategoryWithTrailingExclamation() {
        var r = action.parseClassificationResponse("normal!", "urgent, normal, low", false, false);
        assertEquals(cat(r), "normal");
    }

    @Test
    public void testResponseWithCommonPrefix() {
        var r = action.parseClassificationResponse("Classification: normal", "urgent, normal, low", false, false);
        assertEquals(cat(r), "normal");
    }

    @Test
    public void testResponseWithCategoryPrefix() {
        var r = action.parseClassificationResponse("Category: low", "urgent, normal, low", false, false);
        assertEquals(cat(r), "low");
    }

    @Test
    public void testResponseWithMultipleLines_usesFirstLine() {
        var r = action.parseClassificationResponse("urgent\nSome explanation here.", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    // ── Pipe-delimited responses ───────────────────────────────────────────────

    @Test
    public void testPipeWithConfidence_parsed() {
        var r = action.parseClassificationResponse("urgent|0.95", "urgent, normal, low", true, false);
        assertEquals(cat(r), "urgent");
        assertEquals(conf(r), "0.95");
        assertNull(expl(r));
    }

    @Test
    public void testPipeWithExplanation_noConfidence() {
        var r = action.parseClassificationResponse(
            "urgent|Contains URGENT keyword", "urgent, normal, low", false, true);
        assertEquals(cat(r), "urgent");
        assertNull(conf(r));
        assertEquals(expl(r), "Contains URGENT keyword");
    }

    @Test
    public void testPipeWithConfidenceAndExplanation() {
        var r = action.parseClassificationResponse(
            "urgent|0.95|Contains URGENT keyword", "urgent, normal, low", true, true);
        assertEquals(cat(r), "urgent");
        assertEquals(conf(r), "0.95");
        assertEquals(expl(r), "Contains URGENT keyword");
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testPipeWithInvalidCategory_throws() {
        // First segment is not a valid category
        action.parseClassificationResponse("0.95|urgent|explanation", "urgent, normal, low", true, true);
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testPipeWithUnrecognizedCategory_throws() {
        action.parseClassificationResponse("spam|0.8", "urgent, normal, low", true, false);
    }

    // ── No match ──────────────────────────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testUnrecognizedCategory_throws() {
        action.parseClassificationResponse("maybe", "urgent, normal, low", false, false);
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testPartialMatchDoesNotPass() {
        // "abnormal" contains "normal" but is not an exact match
        action.parseClassificationResponse("abnormal", "urgent, normal, low", false, false);
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() {
        var r = action.parseClassificationResponse(
            "<think>Let me analyze this carefully...</think>urgent", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    @Test
    public void testUnclosedThinkingBlockStripped() {
        var r = action.parseClassificationResponse(
            "urgent<think>Still thinking...", "urgent, normal, low", false, false);
        assertEquals(cat(r), "urgent");
    }

    // ── Model token limits ────────────────────────────────────────────────────

    @Test
    public void testMaxOutputTokensCapRespected() {
        assertTrue(ClassifyText.MAX_OUTPUT_TOKENS > 0,
            "MAX_OUTPUT_TOKENS should be a positive constant");
    }

    @Test
    public void testClassificationTemperatureIsLow() {
        assertTrue(ClassifyText.CLASSIFICATION_TEMPERATURE <= 0.2f,
            "Classification temperature should be <= 0.2 for deterministic results");
    }

    @Test
    public void testAllModelsHavePositiveOutputTokenLimit() {
        String[] models = {"qwen2.5-3b", "llama3.2-3b", "phi3.5-mini", "gemma-2b", "gemma4-e2b", "deepseek-r1-1.5b"};
        for (String modelId : models) {
            com.automationanywhere.botcommand.utils.ModelManager.ModelType type =
                com.automationanywhere.botcommand.utils.ModelManager.ModelType.fromId(modelId);
            int effective = Math.min(ClassifyText.MAX_OUTPUT_TOKENS, type.getMaxOutputTokens());
            assertTrue(effective > 0, "Effective token limit should be positive for " + modelId);
            assertTrue(effective <= type.getMaxOutputTokens(),
                "Effective token limit must not exceed model max for " + modelId);
        }
    }
}
