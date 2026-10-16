package io.github.ddxpromax.webcoder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = "*")
public class AgentsController {
    private static final Logger log =
        LoggerFactory.getLogger(AgentsController.class);

    private final AgentStore store;

    public AgentsController(AgentStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> listAgents() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map<String, Object> profile : store.listProfiles()) {
            agents.add(toAgentInfo(profile));
        }
        return Map.of("agents", agents);
    }

    @GetMapping("/{agentId}")
    public Map<String, Object> getAgent(
        @PathVariable String agentId
    ) {
        return toProfileConfig(requireProfile(agentId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createAgent(
        @RequestBody Map<String, Object> body
    ) {
        var profile = store.createAgent(body);
        return ResponseEntity.status(201).body(toProfileRef(profile));
    }

    @PostMapping("/{agentId}/copy")
    public ResponseEntity<Map<String, Object>> copyAgent(
        @PathVariable String agentId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        var source = requireProfile(agentId);
        Map<String, Object> request = body == null ? Map.of() : body;
        String newId = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "agent-"
                + UUID.randomUUID().toString().substring(0, 8);
            if (store.getProfile(candidate) == null) {
                newId = candidate;
                break;
            }
        }
        if (newId == null) {
            throw new IllegalStateException(
                "Failed to generate unique agent ID after 10 attempts"
            );
        }

        String name = text(request.get("name"),
            text(source.get("name"), agentId) + " Copy");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", newId);
        spec.put("name", name);
        spec.put("description", source.get("description"));
        spec.put("backend", source.get("backend"));
        spec.put("language", source.get("language"));
        spec.put("active_model", source.get("active_model"));
        var profile = store.createAgent(spec);

        if (Boolean.TRUE.equals(request.get("copy_skills"))) {
            copySkills(
                store.workspaceDirForAgent(agentId),
                store.workspaceDirForAgent(newId)
            );
        }
        return ResponseEntity.status(201).body(toProfileRef(profile));
    }

    @PutMapping("/{agentId}")
    public Map<String, Object> updateAgent(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        requireProfile(agentId);
        Map<String, Object> updates = new LinkedHashMap<>();
        for (String key : List.of(
            "name", "description", "backend", "language"
        )) {
            if (body.get(key) != null) {
                updates.put(key, body.get(key));
            }
        }
        updates.put("active_model", body.get("active_model"));
        return toProfileConfig(
            store.updateAgent(agentId, updates)
        );
    }

    @PutMapping("/order")
    public Map<String, Object> reorderAgents(
        @RequestBody Map<String, Object> body
    ) {
        List<String> ids = stringList(body.get("agent_ids"));
        store.setAgentOrder(ids);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("agent_ids", ids);
        return result;
    }

    @PatchMapping("/{agentId}/toggle")
    public Map<String, Object> toggleAgent(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        requireProfile(agentId);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        store.setAgentEnabled(agentId, enabled);
        return Map.of(
            "success", true,
            "agent_id", agentId,
            "enabled", enabled
        );
    }

    @PatchMapping("/{agentId}/pin")
    public Map<String, Object> setAgentPinned(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        requireProfile(agentId);
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        store.setAgentPinned(agentId, pinned);
        return Map.of(
            "success", true,
            "agent_id", agentId,
            "pinned", pinned
        );
    }

    @PatchMapping("/{agentId}/backend-settings")
    public Map<String, Object> updateBackendSettings(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        requireProfile(agentId);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("backend_settings", body);
        Object model = body.get("model");
        if (model != null && !String.valueOf(model).isBlank()) {
            String selected = String.valueOf(model);
            int slash = selected.indexOf('/');
            if (slash > 0 && slash < selected.length() - 1) {
                updates.put("active_model", Map.of(
                    "provider_id", selected.substring(0, slash),
                    "model", selected.substring(slash + 1)
                ));
            }
        }
        store.updateAgent(agentId, updates);
        return toProfileConfig(requireProfile(agentId));
    }

    @DeleteMapping("/{agentId}")
    public Map<String, Object> deleteAgent(
        @PathVariable String agentId
    ) {
        requireProfile(agentId);
        store.deleteAgent(agentId);
        return Map.of("success", true, "agent_id", agentId);
    }

    @ExceptionHandler(AgentMissingException.class)
    public ResponseEntity<Map<String, String>> missingAgent(
        AgentMissingException exception
    ) {
        return ResponseEntity.status(404).body(
            Map.of("detail", exception.getMessage())
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidAgent(
        IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(
            Map.of("detail", exception.getMessage())
        );
    }

    private Map<String, Object> requireProfile(String agentId) {
        Map<String, Object> profile = store.getProfile(agentId);
        if (profile == null) {
            throw new AgentMissingException(
                "Agent '" + agentId + "' not found"
            );
        }
        return profile;
    }

    private static Map<String, Object> toAgentInfo(
        Map<String, Object> profile
    ) {
        String id = text(profile.get("id"), "");
        boolean enabled = Boolean.TRUE.equals(profile.get("enabled"));
        String backend = text(profile.get("backend"), "majo");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", text(profile.get("name"), id));
        result.put(
            "description", text(profile.get("description"), "")
        );
        result.put(
            "workspace_dir", text(profile.get("workspace_dir"), "")
        );
        result.put("enabled", enabled);
        result.put(
            "pinned",
            AgentStore.DEFAULT_AGENT_ID.equals(id)
                || Boolean.TRUE.equals(profile.get("pinned"))
        );
        result.put("startup_status", enabled ? "running" : "disabled");
        result.put("backend", backend);
        result.put(
            "backend_capabilities",
            backendCapabilities(backend)
        );
        if (profile.get("active_model") != null) {
            result.put("active_model", profile.get("active_model"));
        }
        if (profile.get("backend_settings") instanceof Map<?, ?> settings) {
            if (settings.get("model") != null) {
                result.put(
                    "backend_model", String.valueOf(settings.get("model"))
                );
            }
            if (settings.get("reasoning_effort") != null) {
                result.put(
                    "backend_reasoning_effort",
                    String.valueOf(settings.get("reasoning_effort"))
                );
            }
        }
        return result;
    }

    private static Map<String, Object> backendCapabilities(
        String backend
    ) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("workspace_ui", true);
        caps.put("code_files", true);
        if ("majo".equals(backend)) {
            return caps;
        }
        caps.put("model_selection", true);
        caps.put("reasoning_effort", true);
        caps.put("authentication", false);
        caps.put("reasoning_stream", true);
        caps.put("tool_stream", true);
        caps.put("session_resume", true);
        caps.put("native_skills_ui", true);
        caps.put("native_tools_ui", true);
        caps.put("native_mcp_ui", false);
        caps.put("loop_modes", false);
        caps.put("attachments", false);
        caps.put("context_usage", false);
        caps.put("skills_commands", false);
        caps.put("majo_skills_projection", false);
        caps.put("majo_mcp_projection", false);
        caps.put("provider_skills_discovery", false);
        caps.put("provider_mcp_discovery", false);
        caps.put("mcp_tool_allowlist", false);
        caps.put("commands", List.of());
        caps.put("approval_presets", List.of());
        return caps;
    }

    private static Map<String, Object> toProfileConfig(
        Map<String, Object> profile
    ) {
        String id = text(profile.get("id"), "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", text(profile.get("name"), id));
        result.put(
            "description", text(profile.get("description"), "")
        );
        result.put(
            "workspace_dir", text(profile.get("workspace_dir"), "")
        );
        result.put("backend", text(profile.get("backend"), "majo"));
        result.put("language", text(profile.get("language"), "en"));
        for (String key : List.of(
            "backend_settings", "active_model"
        )) {
            if (profile.get(key) != null) {
                result.put(key, profile.get(key));
            }
        }
        return result;
    }

    private static Map<String, Object> toProfileRef(
        Map<String, Object> profile
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.get("id"));
        result.put("workspace_dir", profile.get("workspace_dir"));
        result.put("enabled", profile.get("enabled"));
        result.put("pinned", profile.get("pinned"));
        return result;
    }

    private static String text(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private static void copySkills(Path source, Path target) {
        Path sourceSkills = source.resolve("skills");
        if (!Files.isDirectory(sourceSkills)) {
            return;
        }
        try {
            Path targetSkills = target.resolve("skills");
            Files.createDirectories(targetSkills);
            try (var paths = Files.walk(sourceSkills)) {
                paths.forEach(path -> {
                    try {
                        Path destination = targetSkills.resolve(
                            sourceSkills.relativize(path)
                        );
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(
                                path,
                                destination,
                                StandardCopyOption.REPLACE_EXISTING
                            );
                        }
                    } catch (IOException exception) {
                        log.warn("Could not copy skill {}", path, exception);
                    }
                });
            }
            Path manifest = source.resolve("skill.json");
            if (Files.isRegularFile(manifest)) {
                Files.copy(
                    manifest,
                    target.resolve("skill.json"),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            log.warn("Could not copy agent skills", exception);
        }
    }

    private static final class AgentMissingException
        extends RuntimeException {
        private AgentMissingException(String message) {
            super(message);
        }
    }
}
