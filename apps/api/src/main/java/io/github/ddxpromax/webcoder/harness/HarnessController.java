package io.github.ddxpromax.webcoder.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo-compatible third-party coding-agent harness endpoints. */
@RestController
@RequestMapping("/api/harnesses")
@CrossOrigin(origins = "*")
public class HarnessController {
    private final HarnessService harnesses;

    public HarnessController(HarnessService harnesses) {
        this.harnesses = harnesses;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("providers", harnesses.providers());
    }

    @GetMapping("/{providerId}/models")
    public ResponseEntity<?> models(@PathVariable String providerId) {
        try {
            Map<String, Object> capabilities = harnesses.capabilities(providerId);
            return ResponseEntity.ok(Map.of("models",
                Boolean.TRUE.equals(capabilities.get("model_selection"))
                    ? defaultModels(providerId) : List.of()));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @GetMapping("/{providerId}/mcp")
    public ResponseEntity<?> mcp(@PathVariable String providerId) {
        try {
            harnesses.capabilities(providerId);
            return ResponseEntity.ok(Map.of("servers", List.of()));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @GetMapping("/{providerId}/skills")
    public ResponseEntity<?> skills(@PathVariable String providerId) {
        try {
            harnesses.capabilities(providerId);
            return ResponseEntity.ok(Map.of("skills", List.of()));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @PostMapping("/{providerId}/status")
    public ResponseEntity<?> status(
        @PathVariable String providerId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(harnesses.providerStatus(providerId,
                settings(body)));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @PostMapping("/{providerId}/login")
    public ResponseEntity<?> login(
        @PathVariable String providerId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            boolean deviceCode = body != null
                && Boolean.TRUE.equals(body.get("device_code"));
            return ResponseEntity.ok(harnesses.login(providerId, deviceCode,
                settings(body)));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @PostMapping("/{providerId}/logout")
    public ResponseEntity<?> logout(@PathVariable String providerId) {
        try {
            harnesses.logout(providerId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settings(Map<String, Object> body) {
        if (body == null || !(body.get("settings") instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private ResponseEntity<Object> failure(Exception exception) {
        String detail = exception.getMessage() == null
            ? String.valueOf(exception) : exception.getMessage();
        if (detail.contains("Unknown third-party agent backend")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", detail));
        }
        if (detail.contains("not available yet")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("detail", detail));
        }
        if (detail.contains("not installed")) {
            return ResponseEntity.badRequest().body(Map.of("detail", detail));
        }
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private List<Map<String, Object>> defaultModels(String providerId) {
        List<Map<String, Object>> models = new ArrayList<>();
        if ("codex".equals(providerId)) {
            models.add(model("gpt-5.2-codex", "GPT-5.2-Codex",
                "Codex default model", true,
                List.of("low", "medium", "high"), "medium"));
            models.add(model("gpt-5.1-codex", "GPT-5.1-Codex",
                "Previous Codex model", false,
                List.of("low", "medium", "high"), "medium"));
        } else if ("qoder".equals(providerId)) {
            models.add(model("qoder-2.1", "Qoder 2.1",
                "Qoder default model", true, List.of(), null));
            models.add(model("qoder-2.0", "Qoder 2.0",
                "Qoder previous model", false, List.of(), null));
        }
        return models;
    }

    private Map<String, Object> model(
        String id, String name, String description, boolean isDefault,
        List<String> efforts, String defaultEffort
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("description", description);
        result.put("is_default", isDefault);
        result.put("reasoning_efforts", efforts);
        result.put("default_reasoning_effort", defaultEffort);
        return result;
    }
}
