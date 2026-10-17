package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import java.util.List;
import java.util.Map;

/** Exposes Majo's configured agent inventory to an agent turn. */
public class ListAgentsTool {
    private final AgentStore agents;
    public ListAgentsTool(AgentStore agents) { this.agents = agents; }

    @Tool(name = "list_agents", description = "List configured local agents", readOnly = true, concurrencySafe = true)
    public String listAgents() {
        List<Map<String, Object>> profiles = agents.listProfiles();
        if (profiles.isEmpty()) return "No agents are configured.";
        StringBuilder result = new StringBuilder("Configured agents:\n");
        for (Map<String, Object> profile : profiles) {
            String id = String.valueOf(profile.getOrDefault("id", ""));
            String name = String.valueOf(profile.getOrDefault("name", id));
            boolean enabled = Boolean.TRUE.equals(profile.get("enabled"));
            result.append("- ").append(id).append(" (").append(name).append(") — ")
                .append(enabled ? "enabled" : "disabled").append('\n');
        }
        return result.toString().stripTrailing();
    }
}
