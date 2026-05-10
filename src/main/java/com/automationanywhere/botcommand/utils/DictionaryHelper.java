package com.automationanywhere.botcommand.utils;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds DictionaryValue return values with consistent key ordering.
 * All actions return a dictionary that always includes "status" and "message" keys last.
 */
public class DictionaryHelper {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    /** Single primary result field + status/message. */
    public static DictionaryValue success(String primaryKey, String primaryValue, String message) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>();
        map.put(primaryKey, new StringValue(primaryValue));
        map.put("status", new StringValue(STATUS_SUCCESS));
        map.put("message", new StringValue(message));
        return build(map);
    }

    /** Multiple result fields (caller builds the map) + appended status/message. */
    public static DictionaryValue success(LinkedHashMap<String, Value<?>> fields, String message) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>(fields);
        map.put("status", new StringValue(STATUS_SUCCESS));
        map.put("message", new StringValue(message));
        return build(map);
    }

    public static DictionaryValue error(String message) {
        LinkedHashMap<String, Value<?>> map = new LinkedHashMap<>();
        map.put("status", new StringValue(STATUS_ERROR));
        map.put("message", new StringValue(message));
        return build(map);
    }

    @SuppressWarnings("unchecked")
    private static DictionaryValue build(LinkedHashMap<String, Value<?>> map) {
        return new DictionaryValue((Map<String, Value>) (Map<?, ?>) map);
    }
}
