package com.automationanywhere.botcommand.utils;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds DictionaryValue return values with a consistent standard envelope.
 *
 * Every action returns these four fields last, in this order:
 *   status        → "success" | "error"
 *   model         → model ID used (e.g. "qwen2.5-3b"), or "" for non-inference actions
 *   elapsed_ms    → wall-clock time in milliseconds as a string
 *   error_message → "" on success; human-readable error text on failure
 *
 * Action-specific payload fields (response, category, json, etc.) come first.
 */
public class DictionaryHelper {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    /** Single primary result field + standard envelope. */
    public static DictionaryValue success(String primaryKey, String primaryValue, String model, long elapsedMs) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>();
        map.put(primaryKey, new StringValue(primaryValue));
        appendEnvelope(map, STATUS_SUCCESS, model, elapsedMs, "");
        return build(map);
    }

    /** Multiple result fields (caller builds the map) + standard envelope appended. */
    public static DictionaryValue success(LinkedHashMap<String, Value<?>> fields, String model, long elapsedMs) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>(fields);
        appendEnvelope(map, STATUS_SUCCESS, model, elapsedMs, "");
        return build(map);
    }

    /** Error result — no payload fields, standard envelope with error_message populated. */
    public static DictionaryValue error(String errorMessage, String model, long elapsedMs) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>();
        appendEnvelope(map, STATUS_ERROR, model, elapsedMs, errorMessage != null ? errorMessage : "");
        return build(map);
    }

    private static void appendEnvelope(LinkedHashMap<String, Value<?>> map,
                                       String status, String model, long elapsedMs, String errorMessage) {
        map.put("status", new StringValue(status));
        map.put("model", new StringValue(model != null ? model : ""));
        map.put("elapsed_ms", new StringValue(String.valueOf(elapsedMs)));
        map.put("error_message", new StringValue(errorMessage));
    }

    @SuppressWarnings("unchecked")
    private static DictionaryValue build(LinkedHashMap<String, Value<?>> map) {
        return new DictionaryValue((Map<String, Value>) (Map<?, ?>) map);
    }
}
