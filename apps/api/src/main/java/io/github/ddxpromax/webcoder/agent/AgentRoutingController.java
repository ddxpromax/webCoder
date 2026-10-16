package io.github.ddxpromax.webcoder.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo agent-scoped LLM routing configuration compatibility path. */
@RestController
@RequestMapping("/api/agents/{agentId}/config/agents")
@CrossOrigin(origins = "*")
public class AgentRoutingController {
    private final AgentStore agents;

    public AgentRoutingController(AgentStore agents) { this.agents = agents; }

    @GetMapping("/llm-routing")
    public ResponseEntity<?> get(@PathVariable String agentId) {
        Map<String, Object> profile = agents.getProfile(agentId);
        if (profile == null) return ResponseEntity.status(404).body(Map.of("detail", "Agent '" + agentId + "' not found"));
        Object routing = profile.get("llm_routing");
        return ResponseEntity.ok(routing instanceof Map<?, ?> ? routing : new LinkedHashMap<>());
    }

    @PutMapping("/llm-routing")
    public ResponseEntity<?> update(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        if (agents.getProfile(agentId) == null) return ResponseEntity.status(404).body(Map.of("detail", "Agent '" + agentId + "' not found"));
        Map<String, Object> copy = new LinkedHashMap<>(body);
        agents.updateAgent(agentId, Map.of("llm_routing", copy));
        return ResponseEntity.ok(copy);
    }
}
