package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import java.time.LocalDate;
import java.util.Map;

/** Majo's current-day token report available to the agent. */
public class TokenUsageTool {
    private final String agentId;
    private final TokenUsageService usage;

    public TokenUsageTool(String agentId, TokenUsageService usage) {
        this.agentId = agentId;
        this.usage = usage;
    }

    @Tool(
        name = "get_token_usage",
        description = "Get today's model token usage by model and agent",
        readOnly = true,
        concurrencySafe = true
    )
    public String getTokenUsage() {
        String today = LocalDate.now().toString();
        Map<String, Object> summary = usage.summary(
            today, today, null, null
        );
        StringBuilder result = new StringBuilder()
            .append("Token usage for ").append(today)
            .append(": input=")
            .append(summary.get("total_prompt_tokens"))
            .append(", output=")
            .append(summary.get("total_completion_tokens"))
            .append(", calls=")
            .append(summary.get("total_calls"));
        for (Map<String, Object> detail : usage.details(
            today, today, null, null
        )) {
            result.append("\n  ");
            String provider = String.valueOf(
                detail.get("provider_id")
            );
            if (!provider.isBlank()) {
                result.append(provider).append(':');
            }
            result.append(detail.get("model"))
                .append(": input=")
                .append(detail.get("prompt_tokens"))
                .append(", output=")
                .append(detail.get("completion_tokens"))
                .append(", calls=")
                .append(detail.get("call_count"));
        }
        for (Map<String, Object> agent : usage.agentStatsSince(
            today
        )) {
            result.append("\n  agent ")
                .append(agent.get("agent_id"));
            if (agentId.equals(agent.get("agent_id"))) {
                result.append(" (current)");
            }
            result.append(": input=")
                .append(agent.get("input_tokens"))
                .append(", output=")
                .append(agent.get("output_tokens"))
                .append(", turns=")
                .append(agent.get("turns"));
        }
        return result.toString();
    }
}
