package io.github.ddxpromax.webcoder.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Separate public MCP endpoint values from credential-backed values. */
final class McpBindings {
    private static final Set<String> PUBLIC_HEADERS = Set.of(
        "accept", "content-type", "user-agent", "x-client-name"
    );
    private static final Set<String> PUBLIC_ENV = Set.of(
        "NODE_ENV", "LOG_LEVEL", "DEBUG", "MCP_MODE"
    );

    private McpBindings() {}

    static boolean secret(String section, String name) {
        if ("headers".equals(section)) {
            return !PUBLIC_HEADERS.contains(name.toLowerCase());
        }
        return !PUBLIC_ENV.contains(name.toUpperCase());
    }

    static String credentialField(String section, String name) {
        if ("env".equals(section)) {
            return name;
        }
        String normalized = name.trim().toLowerCase()
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "secret" : normalized;
    }

    static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int size = value.length();
        if (size <= 8) {
            return "*".repeat(size);
        }
        if (size <= 12) {
            return value.substring(0, 1)
                + "*".repeat(Math.max(size - 2, 4))
                + value.substring(size - 1);
        }
        int prefix = size > 2 && value.charAt(2) == '-'
            ? 3 : 2;
        int suffix = size >= 16 ? 4 : 2;
        return value.substring(0, prefix)
            + "*".repeat(Math.max(size - prefix - suffix, 4))
            + value.substring(size - suffix);
    }

    static Map<String, Object> encode(
        String section,
        Map<String, String> values,
        Map<String, String> priorSecrets,
        Map<String, String> newSecrets
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null
                ? "" : entry.getValue();
            if (!secret(section, key)) {
                result.put(key, Map.of(
                    "source", "literal", "value", value
                ));
                continue;
            }
            String field = credentialField(section, key);
            if ("headers".equals(section)) {
                String base = field;
                int index = 2;
                while (newSecrets.containsKey(field)) {
                    field = base + "_" + index++;
                }
            }
            String previous = priorSecrets.getOrDefault(field, "");
            newSecrets.put(field,
                value.equals(mask(previous)) ? previous : value
            );
            result.put(key, Map.of(
                "source", "credential",
                "credential", "static",
                "field", field
            ));
        }
        return result;
    }

    static Map<String, String> present(
        Object raw,
        Map<String, String> secrets
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : McpValues.map(raw).entrySet()) {
            Map<String, Object> spec = McpValues.map(entry.getValue());
            if ("literal".equals(spec.get("source"))) {
                result.put(entry.getKey(), McpValues.string(
                    spec.get("value")
                ));
            } else if ("credential".equals(spec.get("source"))) {
                result.put(entry.getKey(), mask(secrets.getOrDefault(
                    McpValues.string(spec.get("field")), ""
                )));
            }
        }
        return result;
    }

    static Map<String, String> resolve(
        Object raw,
        Map<String, String> secrets
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : McpValues.map(raw).entrySet()) {
            Map<String, Object> spec = McpValues.map(entry.getValue());
            String source = McpValues.string(spec.get("source"));
            if ("literal".equals(source)) {
                result.put(entry.getKey(), McpValues.string(
                    spec.get("value")
                ));
            } else if ("credential".equals(source)) {
                String value = secrets.getOrDefault(
                    McpValues.string(spec.get("field")), ""
                );
                String format = McpValues.string(spec.get("format"));
                result.put(entry.getKey(), format.isBlank() ? value
                    : format.replace("{value}", value));
            }
        }
        return result;
    }
}
