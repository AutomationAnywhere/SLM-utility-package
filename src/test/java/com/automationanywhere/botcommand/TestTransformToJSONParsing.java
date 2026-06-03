package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.exception.BotCommandException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for TransformToJSON.parseAndValidateJSON().
 * These tests exercise the parsing and validation logic directly — no model download required.
 */
public class TestTransformToJSONParsing {

    private TransformToJSON action;

    @BeforeClass
    public void setUp() {
        action = new TransformToJSON();
    }

    // ── Empty / null responses ─────────────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testNullResponse_throws() throws BotCommandException {
        action.parseAndValidateJSON(null, "compact", "object");
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testEmptyResponse_throws() throws BotCommandException {
        action.parseAndValidateJSON("", "compact", "object");
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testBlankResponse_throws() throws BotCommandException {
        action.parseAndValidateJSON("   ", "compact", "object");
    }

    // ── No JSON structure found ────────────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testNoJsonStructure_throws() throws BotCommandException {
        action.parseAndValidateJSON("The answer is 42", "compact", "object");
    }

    // ── Valid object → object ──────────────────────────────────────────────────

    @Test
    public void testValidObject_compact() throws BotCommandException {
        String result = action.parseAndValidateJSON("{\"name\":\"John\",\"age\":30}", "compact", "object");
        assertNotNull(result);
        assertTrue(result.contains("John"));
        assertFalse(result.contains("\n"), "Compact output should not contain newlines");
    }

    @Test
    public void testValidObject_pretty() throws BotCommandException {
        String result = action.parseAndValidateJSON("{\"name\":\"John\"}", "pretty", "object");
        assertNotNull(result);
        assertTrue(result.contains("\n"), "Pretty output should contain newlines");
    }

    @Test
    public void testObjectWithMarkdownFence_stripped() throws BotCommandException {
        String result = action.parseAndValidateJSON("```json\n{\"key\":\"val\"}\n```", "compact", "object");
        assertNotNull(result);
        assertFalse(result.contains("```"));
    }

    @Test
    public void testObjectWithLeadingText_extracted() throws BotCommandException {
        String result = action.parseAndValidateJSON("Here is the JSON: {\"name\":\"Alice\"}", "compact", "object");
        assertNotNull(result);
        assertTrue(result.contains("Alice"));
    }

    // ── Valid array → array ────────────────────────────────────────────────────

    @Test
    public void testValidArray_compact() throws BotCommandException {
        String result = action.parseAndValidateJSON("[{\"name\":\"A\"},{\"name\":\"B\"}]", "compact", "array");
        assertNotNull(result);
        assertTrue(result.trim().startsWith("["));
        assertTrue(result.trim().endsWith("]"));
    }

    @Test
    public void testStringArray() throws BotCommandException {
        String result = action.parseAndValidateJSON("[\"Apple\",\"Banana\",\"Orange\"]", "compact", "array");
        assertNotNull(result);
        assertTrue(result.trim().startsWith("["));
    }

    // ── Type mismatch — no silent data manipulation ────────────────────────────

    @Test
    public void testObjectWhenArrayRequested_throwsWithHelpfulMessage() {
        try {
            action.parseAndValidateJSON("{\"name\":\"John\"}", "compact", "array");
            fail("Should throw when object is returned but array was requested");
        } catch (BotCommandException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("object") || msg.contains("array"),
                "Error message should mention the type mismatch");
            assertTrue(msg.contains("Output Type"),
                "Error message should suggest changing Output Type");
        }
    }

    @Test
    public void testArrayWhenObjectRequested_throwsWithHelpfulMessage() {
        try {
            action.parseAndValidateJSON("[{\"name\":\"A\"},{\"name\":\"B\"}]", "compact", "object");
            fail("Should throw when array is returned but object was requested");
        } catch (BotCommandException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("array") || msg.contains("object"),
                "Error message should mention the type mismatch");
            assertTrue(msg.contains("Output Type"),
                "Error message should suggest changing Output Type");
        }
    }

    // ── Invalid JSON syntax ────────────────────────────────────────────────────

    @Test(expectedExceptions = BotCommandException.class)
    public void testMalformedJSON_throws() throws BotCommandException {
        action.parseAndValidateJSON("{\"name\":\"John\"", "compact", "object"); // missing closing brace
    }

    // ── DeepSeek R1 thinking blocks ───────────────────────────────────────────

    @Test
    public void testThinkingBlockStripped() throws BotCommandException {
        String response = "<think>Converting CSV to JSON now...</think>{\"name\":\"John\"}";
        String result = action.parseAndValidateJSON(response, "compact", "object");
        assertNotNull(result);
        assertFalse(result.contains("<think>"));
        assertTrue(result.contains("John"));
    }

    // ── Model token limits ────────────────────────────────────────────────────

    @Test
    public void testAllModelsHavePositiveOutputTokenLimit() {
        String[] models = {"qwen3-4b", "llama3.2-3b", "phi4-mini", "gemma3-4b", "gemma4-e2b", "deepseek-r1-1.5b"};
        for (String modelId : models) {
            com.automationanywhere.botcommand.utils.ModelManager.ModelType type =
                com.automationanywhere.botcommand.utils.ModelManager.ModelType.fromId(modelId);
            int effective = Math.min(TransformToJSON.MAX_OUTPUT_TOKENS, type.getMaxOutputTokens());
            assertTrue(effective > 0, "Effective token limit should be positive for " + modelId);
            assertTrue(effective <= type.getMaxOutputTokens(),
                "Effective token limit must not exceed model max for " + modelId);
        }
    }

    // ── Grammar minimal-path regression ──────────────────────────────────────
    //
    // When grammar-constrained generation is wired up WITHOUT prompt priming,
    // models take the minimal-valid-JSON path: {} or [].  parseAndValidateJSON
    // must reject these as meaningless output so the caller can retry or surface
    // a useful error.

    @Test(expectedExceptions = BotCommandException.class,
          expectedExceptionsMessageRegExp = ".*[Ee]mpty.*|.*no (?:content|data|fields|properties|key).*|.*Model returned empty.*")
    public void testEmptyObject_rejected() throws BotCommandException {
        // {} is syntactically valid JSON but semantically empty — no useful data
        // was transformed.  The action should NOT silently return {} to the bot.
        action.parseAndValidateJSON("{}", "compact", "object");
    }

    @Test(expectedExceptions = BotCommandException.class,
          expectedExceptionsMessageRegExp = ".*[Ee]mpty.*|.*no (?:content|data|fields|properties|items).*|.*Model returned empty.*")
    public void testEmptyArray_rejected() throws BotCommandException {
        // [] is likewise a no-op transformation.
        action.parseAndValidateJSON("[]", "compact", "array");
    }

    @Test
    public void testNonEmptyObject_accepted() throws BotCommandException {
        // A real transformation result should pass through fine.
        String result = action.parseAndValidateJSON(
            "{\"Name\":\"Maria Chen\",\"Department\":\"Engineering\"}", "compact", "object");
        assertNotNull(result);
        assertTrue(result.contains("Maria Chen"));
    }

    @Test
    public void testNonEmptyArray_accepted() throws BotCommandException {
        String result = action.parseAndValidateJSON(
            "[{\"Name\":\"Alice\"},{\"Name\":\"Bob\"}]", "compact", "array");
        assertNotNull(result);
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("Bob"));
    }

    // ── JsonGrammar constants sanity-check ────────────────────────────────────

    @Test
    public void testJsonGrammarConstantsNonNull() {
        assertNotNull(com.automationanywhere.botcommand.utils.JsonGrammar.ANY);
        assertNotNull(com.automationanywhere.botcommand.utils.JsonGrammar.OBJECT_ONLY);
        assertNotNull(com.automationanywhere.botcommand.utils.JsonGrammar.ARRAY_ONLY);
        assertTrue(com.automationanywhere.botcommand.utils.JsonGrammar.ANY.contains("root"));
        assertTrue(com.automationanywhere.botcommand.utils.JsonGrammar.OBJECT_ONLY.startsWith("root ::= object"));
        assertTrue(com.automationanywhere.botcommand.utils.JsonGrammar.ARRAY_ONLY.startsWith("root ::= array"));
    }

    @Test
    public void testJsonGrammarForOutputType_object() {
        String g = com.automationanywhere.botcommand.utils.JsonGrammar.forOutputType("object");
        assertTrue(g.startsWith("root ::= object"), "object outputType should yield OBJECT_ONLY grammar");
    }

    @Test
    public void testJsonGrammarForOutputType_array() {
        String g = com.automationanywhere.botcommand.utils.JsonGrammar.forOutputType("array");
        assertTrue(g.startsWith("root ::= array"), "array outputType should yield ARRAY_ONLY grammar");
    }

    @Test
    public void testJsonGrammarForOutputType_null() {
        String g = com.automationanywhere.botcommand.utils.JsonGrammar.forOutputType(null);
        assertNotNull(g);
        assertTrue(g.contains("root"), "null outputType should yield ANY grammar");
    }
}
