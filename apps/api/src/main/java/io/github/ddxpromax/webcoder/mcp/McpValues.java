package io.github.ddxpromax.webcoder.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpValues {
    private McpValues() {}

    static Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> source) {
            source.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }

    static Map<String, String> strings(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        map(raw).forEach((key, value) -> result.put(
            key, value == null ? "" : String.valueOf(value)
        ));
        return result;
    }

    static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    static List<String> list(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> values) {
            values.forEach(value -> result.add(String.valueOf(value)));
        }
        return result;
    }

    static List<String> nullableList(Object raw) {
        return raw == null ? null : list(raw);
    }

    static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean value) {
            return value;
        }
        return fallback;
    }

    static Double positiveDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(String.valueOf(raw));
            return Double.isFinite(value) && value > 0
                ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
