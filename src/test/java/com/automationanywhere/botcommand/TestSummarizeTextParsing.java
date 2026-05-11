package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.ModelManager;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for SummarizeText.parseSummaryResponse().
 * No model download required.
 */
public class TestSummarizeTextParsing {

    private SummarizeText action;

    @BeforeClass
    public void setUp() {
        action = new SummarizeText();
    }

    // ── Clean response ────────────────────────────────────────────────────────

    @Test
    public void testCleanSummary_returned() {
        String result = action.parseSummaryResponse("This is a clean summary sentence.");
        assertEquals(result, "This is a clean summary sentence.");
    }

    @Test
    public void testLeadingAndTrailingWhitespaceStripped() {
        String result = action.parseSummaryResponse("  Summary text here.  ");
        assertEquals(result, "Summary text here.");
    }

    // ── Common prefix stripping ───────────────────────────────────────────────

    @Test
    public void testSummaryPrefixStripped() {
        String result = action.parseSummaryResponse("Summary: The meeting covered budget approval.");
        assertEquals(result, "The meeting covered budget approval.");
    }

    @Test
    public void testResultPrefixStripped() {
        String result = action.parseSummaryResponse("Result: Key decision was made.");
        assertEquals(result, "Key decision was made.");
    }

    @Test
    public void testOutputPrefixStripped() {
        String result = action.parseSummaryResponse("Output: The document describes the project plan.");
        assertEquals(result, "The document describes the project plan.");
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() {
        String response = "<think>I need to summarize this in 2-3 sentences.</think>The report covers Q3 sales performance.";
        String result = action.parseSummaryResponse(response);
        assertEquals(result, "The report covers Q3 sales performance.");
    }

    @Test
    public void testUnclosedThinkingBlockStripped() {
        String response = "The meeting resulted in three action items.<think>still thinking";
        String result = action.parseSummaryResponse(response);
        assertEquals(result, "The meeting resulted in three action items.");
    }

    // ── Empty / null — should throw ───────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testNullResponse_throwsBotCommandException() {
        action.parseSummaryResponse(null);
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testEmptyResponse_throwsBotCommandException() {
        action.parseSummaryResponse("");
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testBlankResponse_throwsBotCommandException() {
        action.parseSummaryResponse("   ");
    }

    // ── Multi-line response — kept as-is (summaries can be paragraphs) ────────

    @Test
    public void testMultiLineSummaryPreserved() {
        String multiLine = "First sentence of summary.\nSecond sentence here.";
        String result = action.parseSummaryResponse(multiLine);
        // parseSummaryResponse does NOT truncate to first line for summaries
        assertTrue(result.contains("First sentence"));
    }

    // ── Token limits ──────────────────────────────────────────────────────────

    @Test
    public void testShortTokenBudgetPositive() {
        assertTrue(SummarizeText.MAX_OUTPUT_TOKENS_SHORT > 0);
    }

    @Test
    public void testDetailedTokenBudgetLargerThanShort() {
        assertTrue(SummarizeText.MAX_OUTPUT_TOKENS_DETAILED > SummarizeText.MAX_OUTPUT_TOKENS_SHORT);
    }

    @Test
    public void testAllModelsHavePositiveOutputTokenLimit() {
        String[] models = {"qwen2.5-3b", "llama3.2-3b", "phi3.5-mini", "gemma-2b", "gemma4-e2b", "deepseek-r1-1.5b"};
        for (String modelId : models) {
            ModelManager.ModelType type = ModelManager.ModelType.fromId(modelId);
            int effective = Math.min(SummarizeText.MAX_OUTPUT_TOKENS_DETAILED, type.getMaxOutputTokens());
            assertTrue(effective > 0, "Effective token limit should be positive for " + modelId);
        }
    }
}
