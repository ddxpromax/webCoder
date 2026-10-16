package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CodingModeController {
    private final AgentStore agents;

    public CodingModeController(AgentStore agents) {
        this.agents = agents;
    }

    @GetMapping("/coding-mode")
    public Map<String, Object> get(HttpServletRequest request) {
        String agentId = agentId(request);
        Map<String, Object> profile = requireAgent(agentId);
        Map<String, Object> coding = asMap(profile.get("coding_mode"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", Boolean.TRUE.equals(coding.get("enabled")));
        result.put("project_dir", coding.get("project_dir"));
        result.put("agent_id", agentId);
        return result;
    }

    @PostMapping("/coding-mode")
    public Map<String, Object> update(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String agentId = agentId(request);
        Map<String, Object> coding = asMap(
            requireAgent(agentId).get("coding_mode")
        );
        boolean enabled = Boolean.parseBoolean(
            String.valueOf(body.getOrDefault("enabled", false))
        );
        coding.put("enabled", enabled);
        if (body.containsKey("project_dir")) {
            if (body.get("project_dir") == null) {
                coding.remove("project_dir");
            } else {
                coding.put("project_dir", String.valueOf(
                    body.get("project_dir")
                ));
            }
        }
        agents.updateAgent(agentId, Map.of("coding_mode", coding));
        return Map.of("enabled", enabled, "agent_id", agentId);
    }

    private Map<String, Object> requireAgent(String agentId) {
        Map<String, Object> profile = agents.getProfile(agentId);
        if (profile == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    private static String agentId(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Id");
        if (header == null || header.isBlank()) {
            header = request.getParameter("agent");
        }
        return header == null || header.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : header;
    }
}
