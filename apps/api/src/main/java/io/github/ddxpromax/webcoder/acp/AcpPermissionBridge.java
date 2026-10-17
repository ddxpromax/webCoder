package io.github.ddxpromax.webcoder.acp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ddxpromax.webcoder.agent.ApprovalStore;
import io.github.ddxpromax.webcoder.agent.ToolSessionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps ACP runner permission requests onto the current Majo approval session. */
@Component
public class AcpPermissionBridge {
    private final ApprovalStore approvals;
    public AcpPermissionBridge(ApprovalStore approvals) { this.approvals = approvals; }
    public Map<String, Object> resolve(JsonNode params) { return resolve(params, 60); }
    public Map<String, Object> resolve(JsonNode params, long timeoutSeconds) {
        String sessionId = ToolSessionContext.sessionId();
        if (sessionId.isBlank()) return cancelled();
        String title = params.path("toolCall").path("title").asText("delegated action");
        ApprovalStore.ApprovalRequest request = approvals.register(sessionId, sessionId, ToolSessionContext.agentId(), "ACP:" + title, title, "HIGH", timeoutSeconds);
        if (!"approved".equals(request.await(timeoutSeconds * 1000))) return cancelled();
        String option = firstAllow(params); Map<String, Object> outcome = new LinkedHashMap<>(); if (!option.isBlank()) outcome.put("optionId", option); outcome.put("outcome", "selected"); return Map.of("outcome", outcome);
    }
    private static String firstAllow(JsonNode params) { if (!params.path("options").isArray()) return ""; for (JsonNode option : params.path("options")) { String kind = option.path("kind").asText(); if (("allow_once".equalsIgnoreCase(kind) || "allow_always".equalsIgnoreCase(kind)) && !option.path("optionId").asText().isBlank()) return option.path("optionId").asText(); } return ""; }
    private static Map<String,Object> cancelled() { return Map.of("outcome", Map.of("outcome", "cancelled")); }
}
