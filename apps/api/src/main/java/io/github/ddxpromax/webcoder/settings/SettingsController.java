package io.github.ddxpromax.webcoder.settings;

import java.util.Map;
import io.github.ddxpromax.webcoder.agent.HeartbeatScheduler;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SettingsController {
    private final SettingsService service;
    private final HeartbeatScheduler heartbeat;
    private final AgentStore agents;

    public SettingsController(
        SettingsService service,
        HeartbeatScheduler heartbeat,
        AgentStore agents
    ) {
        this.service = service;
        this.heartbeat = heartbeat;
        this.agents = agents;
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return service.getSettings();
    }

    @PostMapping("/settings")
    public Map<String, String> saveSettings(
        @RequestBody Map<String, String> body
    ) {
        return service.saveSettings(body);
    }

    /** Majo's default action for long-running tool-call offloading. */
    @GetMapping("/settings/offload-policy")
    public Map<String, String> getOffloadPolicy() {
        Object value = agents.loadConfig().get("offload_policy");
        return Map.of("default_action", value == null || String.valueOf(value).isBlank()
            ? "keep_foreground" : String.valueOf(value));
    }

    @PutMapping("/settings/offload-policy")
    public Map<String, String> setOffloadPolicy(
        @RequestBody Map<String, String> body
    ) {
        String action = body.getOrDefault("default_action", "keep_foreground");
        agents.updateRoot("offload_policy", action);
        return Map.of("default_action", action);
    }

    @GetMapping("/config/theme")
    public Map<String, Object> getTheme() {
        return service.getTheme();
    }

    @PutMapping("/config/theme")
    public ResponseEntity<?> updateTheme(
        @RequestBody Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(service.setTheme(
                body.get("accent") == null
                    ? ""
                    : String.valueOf(body.get("accent")),
                body.get("accent_dark") == null
                    ? ""
                    : String.valueOf(body.get("accent_dark"))
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                Map.of("detail", exception.getMessage())
            );
        }
    }

    @DeleteMapping("/config/theme")
    public Map<String, Object> resetTheme() {
        return service.setTheme("", "");
    }

    @GetMapping("/config/heartbeat")
    public Map<String, Object> getHeartbeat() {
        Map<String, Object> config = new java.util.LinkedHashMap<>(service.getHeartbeat());
        config.put("last_run", heartbeat.lastRun());
        return config;
    }

    @PutMapping("/config/heartbeat")
    public ResponseEntity<?> setHeartbeat(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> hours = body.get("active_hours") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            service.setHeartbeat(Boolean.TRUE.equals(body.get("enabled")), String.valueOf(body.getOrDefault("every", "6h")), String.valueOf(body.getOrDefault("target", "main")), ((Number) body.getOrDefault("timeoutSeconds", 120)).intValue(), String.valueOf(hours.getOrDefault("start", "")), String.valueOf(hours.getOrDefault("end", "")));
            heartbeat.reschedule(); return ResponseEntity.ok(getHeartbeat());
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage())); }
    }

    @PostMapping("/config/heartbeat/run")
    public Map<String, Object> runHeartbeat() { return heartbeat.runNow(); }

    @PutMapping("/agents/{agentId}/config/heartbeat")
    public ResponseEntity<?> setAgentHeartbeat(@org.springframework.web.bind.annotation.PathVariable String agentId, @RequestBody Map<String, Object> body) { return setHeartbeat(body); }
    @PostMapping("/agents/{agentId}/config/heartbeat/run")
    public Map<String, Object> runAgentHeartbeat(@org.springframework.web.bind.annotation.PathVariable String agentId) { return runHeartbeat(); }
}
