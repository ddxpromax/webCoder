package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RunningConfigController {
    private final AgentStore agents;
    private final MemoryBackendRegistry memoryBackends;

    public RunningConfigController(
        AgentStore agents,
        MemoryBackendRegistry memoryBackends
    ) {
        this.agents = agents;
        this.memoryBackends = memoryBackends;
    }

    @GetMapping("/workspace/running-config")
    public Map<String, Object> get(HttpServletRequest request) {
        return getFor(agentId(request));
    }

    @GetMapping("/agents/{agentId}/workspace/running-config")
    public Map<String, Object> getFor(
        @PathVariable String agentId
    ) {
        Map<String, Object> merged =
            RunningConfigDefaults.deepMerge(
                RunningConfigDefaults.defaultConfig(),
                agents.getRunningConfig(agentId)
            );
        merged.put("approval_level", agents.getApprovalLevel(agentId));
        return merged;
    }

    @PutMapping("/workspace/running-config")
    public Map<String, Object> update(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return updateFor(agentId(request), body);
    }

    @PutMapping("/agents/{agentId}/workspace/running-config")
    public Map<String, Object> updateFor(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        String level = body.get("approval_level") == null
            ? null : Objects.toString(body.get("approval_level"))
                .trim().toUpperCase();
        Map<String, Object> running = new LinkedHashMap<>(body);
        running.remove("approval_level");
        agents.saveRunningConfig(agentId, running, level);
        memoryBackends.evict(agentId);
        memoryBackends.resolve(agentId);
        return getFor(agentId);
    }

    private static String agentId(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Id");
        return header == null || header.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : header;
    }
}
