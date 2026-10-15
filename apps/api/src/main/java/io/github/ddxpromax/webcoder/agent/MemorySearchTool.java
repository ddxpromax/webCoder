package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/** Search persistent workspace memory with the selected Majo backend. */
public class MemorySearchTool {
    private final String agentId;
    private final MemoryBackendRegistry registry;

    public MemorySearchTool(
        String agentId,
        MemoryBackendRegistry registry
    ) {
        this.agentId = agentId;
        this.registry = registry;
    }

    @Tool(
        name = "memory_search",
        description = "Search the agent's persistent memory for relevant information",
        readOnly = true,
        concurrencySafe = true
    )
    public String search(
        @ToolParam(name = "query", description = "Search terms")
        String query,
        @ToolParam(
            name = "max_results",
            description = "Maximum number of results",
            required = false
        ) Integer maxResults
    ) {
        if (query == null || query.isBlank()) {
            return "query must not be empty";
        }
        MemoryBackend backend = registry.resolve(agentId);
        if (backend == null) {
            return "Memory backend is unavailable.";
        }
        int limit = maxResults == null ? 5
            : Math.max(1, Math.min(20, maxResults));
        List<MemoryHit> hits = backend.search(query, limit);
        if (hits.isEmpty()) {
            return "No matching memories found.";
        }
        StringBuilder result = new StringBuilder();
        for (MemoryHit hit : hits) {
            result.append("- ").append(hit.source());
            if (!hit.snippet().isBlank()) {
                result.append(": ").append(hit.snippet());
            }
            result.append('\n');
        }
        return result.toString().trim();
    }
}
