package io.github.ddxpromax.webcoder.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Majo MCP access rules stored in each DriverCard policy object. */
final class McpPolicy {
    private static final Set<String> EFFECTS = Set.of(
        "allow", "ask", "deny"
    );

    private McpPolicy() {}

    static McpModels.AccessPolicy read(Object raw) {
        Map<String, Object> policy = McpValues.map(raw);
        List<McpModels.AccessRule> clients = new ArrayList<>();
        List<McpModels.ToolDefaultPolicy> defaults =
            new ArrayList<>();
        List<McpModels.ToolAccessOverride> tools =
            new ArrayList<>();
        int unmanaged = 0;
        for (Map<String, Object> rule : rules(policy)) {
            String kind = McpValues.string(McpValues.map(
                rule.get("target")
            ).get("kind"));
            String name = McpValues.string(McpValues.map(
                rule.get("target")
            ).get("name"));
            String effect = McpValues.string(rule.get("effect"));
            if (rule.get("condition") != null
                || !"tool".equals(kind)
                || name.isBlank()
                || !EFFECTS.contains(effect)) {
                unmanaged++;
                continue;
            }
            Map<String, Object> principal = McpValues.map(
                rule.get("principal")
            );
            if (!"*".equals(name)
                && "*".equals(rule.get("subject"))
                && blankPrincipal(principal)) {
                defaults.add(new McpModels.ToolDefaultPolicy(
                    name, effect
                ));
                continue;
            }
            McpModels.AccessRule access = accessRule(
                rule, principal, effect
            );
            if (access == null) {
                unmanaged++;
            } else if ("*".equals(name)) {
                clients.add(access);
            } else {
                tools.add(new McpModels.ToolAccessOverride(
                    access.sourceType(), access.sourceValue(),
                    access.subjectType(), access.subjectValue(),
                    access.effect(), name
                ));
            }
        }
        return new McpModels.AccessPolicy(
            EFFECTS.contains(McpValues.string(
                policy.get("default_effect")
            )) ? McpValues.string(policy.get("default_effect"))
                : "ask",
            clients, defaults, tools, unmanaged
        );
    }

    static Map<String, Object> write(
        Object previous,
        McpModels.AccessPolicy update
    ) {
        if (update == null) {
            throw new IllegalArgumentException(
                "MCP access policy is required"
            );
        }
        String defaultEffect = effectOrAsk(
            update.defaultEffect()
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> old : rules(
            McpValues.map(previous)
        )) {
            if (!managed(old)) {
                result.add(old);
            }
        }
        if (update.toolDefaults() != null) {
            for (var item : update.toolDefaults()) {
                if (item.toolName() == null
                    || item.toolName().isBlank()
                    || "*".equals(item.toolName())) {
                    throw new IllegalArgumentException(
                        "MCP tool default name is empty"
                    );
                }
                result.add(rule(item.toolName(),
                    requireEffect(item.effect()),
                    Map.of(), "*"));
            }
        }
        if (update.clientOverrides() != null) {
            for (var item : update.clientOverrides()) {
                result.add(override("*", item));
            }
        }
        if (update.toolOverrides() != null) {
            for (var item : update.toolOverrides()) {
                if (item.toolName() == null
                    || item.toolName().isBlank()) {
                    throw new IllegalArgumentException(
                        "MCP tool override name is empty"
                    );
                }
                result.add(override(item.toolName(),
                    new McpModels.AccessRule(
                        item.sourceType(), item.sourceValue(),
                        item.subjectType(), item.subjectValue(),
                        item.effect()
                    )));
            }
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("default_effect", defaultEffect);
        policy.put("rules", result);
        return policy;
    }

    static String effect(
        McpModels.AccessPolicy policy,
        String toolName,
        String channel,
        String userId
    ) {
        for (var rule : policy.toolOverrides()) {
            if (toolName.equals(rule.toolName())
                && matches(rule.sourceType(),
                    rule.sourceValue(), rule.subjectType(),
                    rule.subjectValue(), channel, userId)) {
                return rule.effect();
            }
        }
        for (var rule : policy.toolDefaults()) {
            if (toolName.equals(rule.toolName())) {
                return rule.effect();
            }
        }
        for (var rule : policy.clientOverrides()) {
            if (matches(rule.sourceType(),
                rule.sourceValue(), rule.subjectType(),
                rule.subjectValue(), channel, userId)) {
                return rule.effect();
            }
        }
        return policy.defaultEffect();
    }

    private static boolean matches(
        String sourceType,
        String sourceValue,
        String subjectType,
        String subjectValue,
        String channel,
        String userId
    ) {
        if (!"channel".equals(sourceType)
            || !("*".equals(sourceValue)
                || sourceValue.equals(channel))) {
            return false;
        }
        return "all".equals(subjectType)
            || "user".equals(subjectType)
                && subjectValue.equals(userId);
    }

    private static Map<String, Object> override(
        String toolName,
        McpModels.AccessRule access
    ) {
        String sourceType = access.sourceType() == null
            ? "channel" : access.sourceType();
        String sourceValue = access.sourceValue() == null
            ? "" : access.sourceValue().trim();
        String subjectType = access.subjectType() == null
            ? "all" : access.subjectType();
        String subjectValue = access.subjectValue() == null
            ? "" : access.subjectValue().trim();
        if (!"channel".equals(sourceType)
            || sourceValue.isBlank()
            || !Set.of("all", "user").contains(subjectType)
            || "user".equals(subjectType)
                && subjectValue.isBlank()) {
            throw new IllegalArgumentException(
                "Invalid MCP access principal"
            );
        }
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("source_type", sourceType);
        principal.put("source_value", sourceValue);
        principal.put("subject_type", subjectType);
        principal.put("subject_value", "all".equals(subjectType)
            ? "" : subjectValue);
        return rule(toolName, requireEffect(access.effect()),
            principal, "*");
    }

    private static Map<String, Object> rule(
        String name,
        String effect,
        Map<String, Object> principal,
        String subject
    ) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("subject", subject);
        rule.put("effect", effect);
        rule.put("target", Map.of(
            "kind", "tool", "name", name
        ));
        rule.put("principal", principal);
        rule.put("condition", null);
        return rule;
    }

    private static McpModels.AccessRule accessRule(
        Map<String, Object> rule,
        Map<String, Object> principal,
        String effect
    ) {
        String sourceType = McpValues.string(
            principal.get("source_type")
        );
        String sourceValue = McpValues.string(
            principal.get("source_value")
        );
        String subjectType = McpValues.string(
            principal.get("subject_type")
        );
        String subjectValue = McpValues.string(
            principal.get("subject_value")
        );
        if ("channel".equals(sourceType)
            && ("all".equals(subjectType)
                || "user".equals(subjectType))) {
            return new McpModels.AccessRule(
                sourceType, sourceValue, subjectType,
                "all".equals(subjectType)
                    ? "" : subjectValue, effect
            );
        }
        String legacy = McpValues.string(rule.get("subject"));
        if ("*".equals(legacy)) {
            return new McpModels.AccessRule(
                "channel", "console", "all", "", effect
            );
        }
        if (legacy.startsWith("channel:")) {
            return new McpModels.AccessRule(
                "channel", legacy.substring(8), "all", "",
                effect
            );
        }
        if (legacy.startsWith("user:")) {
            return new McpModels.AccessRule(
                "channel", "console", "user",
                legacy.substring(5), effect
            );
        }
        return null;
    }

    private static boolean managed(Map<String, Object> rule) {
        Map<String, Object> target = McpValues.map(
            rule.get("target")
        );
        String effect = McpValues.string(
            rule.get("effect")
        );
        if (rule.get("condition") != null
            || !"tool".equals(target.get("kind"))
            || McpValues.string(target.get("name")).isBlank()
            || !EFFECTS.contains(effect)) {
            return false;
        }
        Map<String, Object> principal = McpValues.map(
            rule.get("principal")
        );
        return !"*".equals(target.get("name"))
            && "*".equals(rule.get("subject"))
            && blankPrincipal(principal)
            || accessRule(rule, principal, effect) != null;
    }

    private static boolean blankPrincipal(
        Map<String, Object> principal
    ) {
        return principal.values().stream().allMatch(value ->
            value == null || "".equals(value)
                || "*".equals(value)
        );
    }

    private static String requireEffect(String effect) {
        if (!EFFECTS.contains(effect)) {
            throw new IllegalArgumentException(
                "MCP effect must be allow, ask, or deny"
            );
        }
        return effect;
    }

    private static String effectOrAsk(String effect) {
        return effect == null || effect.isBlank()
            ? "ask" : requireEffect(effect);
    }

    private static List<Map<String, Object>> rules(
        Map<String, Object> policy
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (policy.get("rules") instanceof List<?> values) {
            for (Object item : values) {
                if (item instanceof Map<?, ?>) {
                    result.add(McpValues.map(item));
                }
            }
        }
        return result;
    }
}
