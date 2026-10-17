package io.github.ddxpromax.webcoder.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Schema-guided repair for model tool calls with scalar type mismatches. */
public final class ToolCallCoercion {
    public record Coerced(Map<String, Object> input, boolean changed) { }
    private ToolCallCoercion() { }

    public static Coerced coerceStringFields(Map<String, Object> input, Map<String, Object> schema) {
        if (input == null || schema == null || schema.isEmpty()) return new Coerced(input, false);
        Map<String, Object> result = map(input, schema);
        return new Coerced(result, !result.equals(input));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> value, Map<String, Object> schema) {
        Object raw = schema.get("properties"); Map<String, Object> properties = raw instanceof Map<?, ?> item ? (Map<String, Object>) item : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(key, node(item, properties.get(key) instanceof Map<?, ?> spec ? (Map<String, Object>) spec : null)));
        return result;
    }
    @SuppressWarnings("unchecked") private static Object node(Object value, Map<String, Object> schema) {
        if (value instanceof Map<?, ?> item) return map((Map<String, Object>) item, schema == null ? Map.of() : schema);
        if (value instanceof List<?> list) { Object raw = schema == null ? null : schema.get("items"); Map<String,Object> items = raw instanceof Map<?, ?> item ? (Map<String,Object>) item : null; List<Object> result = new ArrayList<>(); for (Object entry : list) result.add(node(entry, items)); return result; }
        if ((value instanceof Number || value instanceof Boolean) && !accepts(value, schema) && permitsString(schema)) return String.valueOf(value);
        return value;
    }
    private static boolean accepts(Object value, Map<String,Object> schema) {
        if (schema == null) return true; Set<String> actual = value instanceof Boolean ? Set.of("boolean") : value instanceof Integer || value instanceof Long ? Set.of("integer", "number") : value instanceof Number ? Set.of("number") : value instanceof String ? Set.of("string") : Set.of("null");
        Object type = schema.get("type"); if (type instanceof String text) return actual.contains(text); if (type instanceof List<?> values) return values.stream().anyMatch(item -> actual.contains(String.valueOf(item)));
        for (String key : List.of("anyOf", "oneOf")) if (schema.get(key) instanceof List<?> options) return options.stream().filter(Map.class::isInstance).anyMatch(option -> accepts(value, cast(option)));
        return true;
    }
    private static boolean permitsString(Map<String,Object> schema) { if (schema == null) return false; Object type = schema.get("type"); if ("string".equals(type)) return true; if (type instanceof List<?> types && types.contains("string")) return true; for (String key : List.of("anyOf", "oneOf")) if (schema.get(key) instanceof List<?> options && options.stream().filter(Map.class::isInstance).anyMatch(option -> permitsString(cast(option)))) return true; return false; }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value) { return (Map<String,Object>) value; }
}
