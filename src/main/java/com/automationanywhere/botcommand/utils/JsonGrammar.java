package com.automationanywhere.botcommand.utils;

/**
 * GBNF (GGUF BNF) grammars for grammar-constrained generation via llama-server.
 *
 * When a grammar is passed to llama-server's /completion endpoint, token sampling
 * is constrained at every step so that the output can only form strings that
 * match the grammar.  This makes it physically impossible for the model to produce
 * malformed JSON, preamble text ("Here is your JSON:"), markdown fences, or
 * truncated structures — regardless of model size or quantization level.
 *
 * Grammar variants:
 *   ANY         – valid JSON: either an object {} or an array []
 *   OBJECT_ONLY – valid JSON that must be a top-level object {}
 *   ARRAY_ONLY  – valid JSON that must be a top-level array  []
 *
 * Based on the official llama.cpp JSON grammar:
 *   https://github.com/ggerganov/llama.cpp/blob/master/grammars/json.gbnf
 */
public final class JsonGrammar {

    private JsonGrammar() {}

    // ── shared rules ──────────────────────────────────────────────────────────

    private static final String SHARED_RULES =
        "value  ::= object | array | string | number | (\"true\" | \"false\" | \"null\") ws\n" +
        "object ::= \"{\" ws (string \":\" ws value (\",\" ws string \":\" ws value)*)? \"}\" ws\n" +
        "array  ::= \"[\" ws (value (\",\" ws value)*)? \"]\" ws\n" +
        "string ::= \"\\\"\" ([^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))* \"\\\"\" ws\n" +
        "number ::= \"-\"? ([0-9] | [1-9] [0-9]*) (\".\" [0-9]+)? ([eE] [-+]? [0-9]+)? ws\n" +
        "ws     ::= ([ \\t\\n] ws)?\n";

    // ── public grammar constants ──────────────────────────────────────────────

    /**
     * Any valid JSON value at the top level (object or array).
     * Use when the caller does not care about the top-level structure.
     */
    public static final String ANY =
        "root ::= object | array\n" + SHARED_RULES;

    /**
     * The output MUST be a JSON object {}.
     * Use for single-record key-value transformations.
     */
    public static final String OBJECT_ONLY =
        "root ::= object\n" + SHARED_RULES;

    /**
     * The output MUST be a JSON array [].
     * Use for multi-row / list transformations.
     */
    public static final String ARRAY_ONLY =
        "root ::= array\n" + SHARED_RULES;

    /**
     * Select the appropriate grammar for the requested output type.
     *
     * @param outputType "object", "array", or anything else → ANY
     */
    public static String forOutputType(String outputType) {
        if (outputType == null) return ANY;
        switch (outputType.toLowerCase().trim()) {
            case "object": return OBJECT_ONLY;
            case "array":  return ARRAY_ONLY;
            default:       return ANY;
        }
    }
}
