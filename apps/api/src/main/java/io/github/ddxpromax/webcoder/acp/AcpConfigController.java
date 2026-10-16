package io.github.ddxpromax.webcoder.acp;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo ACP configuration, with per-agent agents and a globally selected Node runtime. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AcpConfigController {
    private static final Set<String> TOOL_PARSE_MODES = Set.of("call_title", "update_detail", "call_detail");
    private final AgentStore agents;

    public AcpConfigController(AgentStore agents) { this.agents = agents; }

    @GetMapping("/config/acp") public Map<String, Object> config(HttpServletRequest request) { return configFor(agentId(request)); }
    @PutMapping("/config/acp") public ResponseEntity<?> update(@RequestBody Map<String, Object> body, HttpServletRequest request) { return saveConfig(agentId(request), body); }
    @GetMapping("/agents/{agentId}/config/acp") public Map<String, Object> agentConfig(@PathVariable String agentId) { return configFor(agentId); }
    @PutMapping("/agents/{agentId}/config/acp") public ResponseEntity<?> updateAgentConfig(@PathVariable String agentId, @RequestBody Map<String, Object> body) { return saveConfig(agentId, body); }
    @GetMapping("/config/acp/node-runtime") public Map<String, Object> nodeRuntime() { return AcpNodeRuntime.status(nodePath()); }
    @GetMapping("/agents/{agentId}/config/acp/node-runtime") public Map<String, Object> agentNodeRuntime(@PathVariable String agentId) { return nodeRuntime(); }

    @PutMapping("/config/acp/node-runtime") public ResponseEntity<?> updateNodeRuntime(@RequestBody Map<String, Object> body) { return saveNodeRuntime(body); }
    @PutMapping("/agents/{agentId}/config/acp/node-runtime") public ResponseEntity<?> updateAgentNodeRuntime(@PathVariable String agentId, @RequestBody Map<String, Object> body) { return saveNodeRuntime(body); }

    @GetMapping("/config/acp/{name}") public ResponseEntity<?> configEntry(@PathVariable String name, HttpServletRequest request) { return entry(agentId(request), name); }
    @PutMapping("/config/acp/{name}") public ResponseEntity<?> updateEntry(@PathVariable String name, @RequestBody Map<String, Object> body, HttpServletRequest request) { return saveEntry(agentId(request), name, body); }
    @GetMapping("/agents/{agentId}/config/acp/{name}") public ResponseEntity<?> agentEntry(@PathVariable String agentId, @PathVariable String name) { return entry(agentId, name); }
    @PutMapping("/agents/{agentId}/config/acp/{name}") public ResponseEntity<?> updateAgentEntry(@PathVariable String agentId, @PathVariable String name, @RequestBody Map<String, Object> body) { return saveEntry(agentId, name, body); }

    private ResponseEntity<?> saveConfig(String agentId, Map<String, Object> input) {
        if (agents.getProfile(agentId) == null) return ResponseEntity.notFound().build();
        Map<String, Object> config = new LinkedHashMap<>(input);
        Map<String, Object> entries = map(input.get("agents"));
        mergeDefaults(entries);
        config.put("agents", entries);
        agents.updateAgent(agentId, Map.of("acp", config));
        return ResponseEntity.ok(configFor(agentId));
    }

    private ResponseEntity<?> entry(String agentId, String name) {
        Object value = map(configFor(agentId).get("agents")).get(name);
        return value == null ? ResponseEntity.status(404).body(Map.of("detail", "ACP agent '" + name + "' not found")) : ResponseEntity.ok(value);
    }

    private ResponseEntity<?> saveEntry(String agentId, String name, Map<String, Object> input) {
        if (!TOOL_PARSE_MODES.contains(string(input.get("tool_parse_mode")))) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid tool_parse_mode. Allowed values: call_detail, call_title, update_detail"));
        }
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().body(Map.of("detail", "ACP agent name cannot be empty"));
        Map<String, Object> config = rawConfig(agentId);
        Map<String, Object> entries = map(config.get("agents"));
        entries.put(name.trim(), new LinkedHashMap<>(input));
        config.put("agents", entries);
        agents.updateAgent(agentId, Map.of("acp", config));
        return ResponseEntity.ok(entries.get(name.trim()));
    }

    private ResponseEntity<?> saveNodeRuntime(Map<String, Object> body) {
        String requested = string(body.get("node_path")).trim();
        if (!requested.isBlank()) {
            Map<String, Object> candidate = AcpNodeRuntime.resolve(requested);
            if (!Boolean.TRUE.equals(candidate.get("available"))) {
                return ResponseEntity.badRequest().body(Map.of("detail", Map.of("reason_code", candidate.get("reason_code"), "reason", candidate.get("reason"))));
            }
        }
        agents.updateRoot("acp_node_path", requested);
        return ResponseEntity.ok(AcpNodeRuntime.status(requested));
    }

    private Map<String, Object> configFor(String agentId) {
        Map<String, Object> config = rawConfig(agentId);
        Map<String, Object> entries = map(config.get("agents"));
        mergeDefaults(entries);
        config.put("agents", entries);
        return config;
    }

    private Map<String, Object> rawConfig(String agentId) {
        Map<String, Object> profile = agents.getProfile(agentId);
        if (profile == null) throw new IllegalArgumentException("Agent '" + agentId + "' not found");
        return map(profile.get("acp"));
    }

    private String nodePath() { return string(agents.loadConfig().get("acp_node_path")); }
    private String agentId(HttpServletRequest request) { String id = request.getHeader("X-Agent-Id"); if (id == null || id.isBlank()) id = request.getParameter("agent"); return id == null || id.isBlank() ? AgentStore.DEFAULT_AGENT_ID : id; }
    private void mergeDefaults(Map<String, Object> entries) { defaults().forEach(entries::putIfAbsent); }
    private Map<String, Object> defaults() { Map<String, Object> values = new LinkedHashMap<>(); values.put("opencode", entry("opencode", List.of("acp"), "update_detail")); values.put("qwen_code", entry("qwen", List.of("--acp"), "call_detail")); values.put("claude_code", entry("npx", List.of("-y", "@zed-industries/claude-agent-acp"), "update_detail")); values.put("codex", entry("npx", List.of("-y", "@zed-industries/codex-acp"), "call_detail")); return values; }
    private Map<String, Object> entry(String command, List<String> arguments, String mode) { Map<String, Object> value = new LinkedHashMap<>(); value.put("enabled", true); value.put("command", command); value.put("args", arguments); value.put("env", Map.of()); value.put("trusted", true); value.put("tool_parse_mode", mode); value.put("stdio_buffer_limit_bytes", 50 * 1024 * 1024); return value; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>(); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
