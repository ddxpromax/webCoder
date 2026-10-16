package io.github.ddxpromax.webcoder.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Register configured MCP servers through AgentScope's MCP client runtime. */
@Component
public class McpRuntime {
    private static final Logger log = LoggerFactory.getLogger(
        McpRuntime.class
    );
    private final McpService service;
    private final Map<Toolkit, Set<String>> registered =
        new ConcurrentHashMap<>();

    public McpRuntime(McpService service) {
        this.service = service;
    }

    public void register(Toolkit toolkit) {
        register(toolkit, service.serverConfigs());
    }

    public String decision(
        Toolkit toolkit,
        String toolName,
        String channel,
        String userId
    ) {
        if (!(toolkit.getTool(toolName) instanceof McpTool tool)) {
            return null;
        }
        try {
            return service.effectFor(tool.getClientName(),
                toolName, channel, userId);
        } catch (McpNotFoundException exception) {
            return "deny";
        }
    }

    private Map<String, McpServerRegistrationResult> register(
        Toolkit toolkit,
        Map<String, McpServerConfig> configs
    ) {
        if (configs.isEmpty()) {
            return Map.of();
        }
        registered.put(toolkit, Set.copyOf(configs.keySet()));
        CountDownLatch completed = new CountDownLatch(
            configs.size()
        );
        Map<String, McpServerRegistrationResult> outcomes =
            new ConcurrentHashMap<>();
        McpServerRegistrar.register(toolkit, configs, result -> {
            outcomes.put(result.serverName(), result);
            completed.countDown();
        });
        try {
            if (!completed.await(15, TimeUnit.SECONDS)) {
                log.warn("MCP registration timed out for {}",
                    configs.keySet());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "MCP registration interrupted", exception
            );
        }
        outcomes.values().stream()
            .filter(result -> result.status()
                != McpServerRegistrationResult.Status.SUCCESS)
            .forEach(result -> log.warn(
                "MCP server '{}' registration failed: {}",
                result.serverName(),
                result.cause() == null ? result.status()
                    : result.cause().getMessage()
            ));
        return outcomes;
    }

    public List<McpModels.ToolInfo> listTools(
        String clientKey
    ) {
        McpModels.ClientInfo client = service.get(clientKey);
        if (!client.enabled()) {
            return List.of();
        }
        McpServerConfig config = service.discoveryConfig(
            clientKey
        );
        if (config == null) {
            return List.of();
        }
        Toolkit toolkit = new Toolkit();
        try {
            Map<String, McpServerRegistrationResult> outcomes =
                register(toolkit, Map.of(clientKey, config));
            McpServerRegistrationResult outcome =
                outcomes.get(clientKey);
            if (outcome == null || outcome.status()
                != McpServerRegistrationResult.Status.SUCCESS) {
                throw new McpUpstreamException(
                    "Failed to query tools from MCP server: "
                        + (outcome == null ? "timed out"
                            : outcome.cause() == null
                                ? outcome.status().toString()
                                : outcome.cause().getMessage())
                );
            }
            List<McpModels.ToolInfo> result = new ArrayList<>();
            for (var schema : toolkit.getToolSchemas()) {
                if (!(toolkit.getTool(schema.getName())
                    instanceof McpTool tool)
                    || !clientKey.equals(tool.getClientName())) {
                    continue;
                }
                boolean enabled = client.tools() == null
                    || client.tools().contains(schema.getName());
                result.add(new McpModels.ToolInfo(
                    schema.getName(),
                    schema.getDescription(),
                    enabled,
                    schema.getParameters()
                ));
            }
            return result;
        } finally {
            close(toolkit);
        }
    }

    public void close(Toolkit toolkit) {
        Set<String> keys = registered.remove(toolkit);
        if (keys != null) {
            close(toolkit, List.copyOf(keys));
        }
    }

    private static void close(
        Toolkit toolkit,
        List<String> clientKeys
    ) {
        for (String key : clientKeys) {
            try {
                toolkit.removeMcpClient(key).block(
                    Duration.ofSeconds(3)
                );
            } catch (Exception exception) {
                log.debug("MCP client '{}' cleanup failed", key,
                    exception);
            }
        }
    }
}
