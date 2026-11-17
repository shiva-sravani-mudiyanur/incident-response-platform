package com.umass.analysis.dto;

import java.util.Map;

/** Captured diagnostics data, never model-authored observations. */
public record Evidence(String id, String toolName, Map<String, Object> data) {
    public Evidence { data = freezeMap(data); }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key, freeze(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<Object, Object>();
            map.forEach((key, item) -> copy.put(key, freeze(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            var copy = new java.util.ArrayList<Object>();
            list.forEach(item -> copy.add(freeze(item)));
            return java.util.Collections.unmodifiableList(copy);
        }
        return value;
    }
}
