package io.github.ddxpromax.webcoder.mcp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

public final class McpModels {
    private McpModels() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientData(
        String name,
        String description,
        Boolean enabled,
        String transport,
        String url,
        Map<String, String> headers,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        Double httpTimeout,
        List<String> tools
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateRequest(String clientKey, ClientData client) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientInfo(
        String key,
        String name,
        String description,
        boolean enabled,
        String transport,
        String url,
        Map<String, String> headers,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        Double httpTimeout,
        List<String> tools,
        OAuthStatus oauthStatus,
        AccessSummary accessSummary
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OAuthStatus(
        boolean authorized,
        double expiresAt,
        String scope,
        String clientId
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessSummary(
        String defaultEffect,
        int overridesCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolInfo(
        String name,
        String description,
        boolean enabled,
        Map<String, Object> inputSchema
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolWhitelistRequest(List<String> tools) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessRule(
        String sourceType,
        String sourceValue,
        String subjectType,
        String subjectValue,
        String effect
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolDefaultPolicy(
        String toolName,
        String effect
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolAccessOverride(
        String sourceType,
        String sourceValue,
        String subjectType,
        String subjectValue,
        String effect,
        String toolName
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessPolicy(
        String defaultEffect,
        List<AccessRule> clientOverrides,
        List<ToolDefaultPolicy> toolDefaults,
        List<ToolAccessOverride> toolOverrides,
        int unmanagedRulesCount
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessPrincipalOption(
        String sourceType,
        String sourceValue,
        String subjectType,
        String subjectValue,
        String label,
        String chatId,
        String chatName,
        String sessionId,
        String updatedAt
    ) {}

    public record Message(String message) {}
}
