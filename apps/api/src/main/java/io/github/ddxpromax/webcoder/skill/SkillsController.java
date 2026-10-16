package io.github.ddxpromax.webcoder.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

/** Majo's workspace skill endpoints, scoped by agent or X-Agent-Id. */
@RestController
@RequestMapping("/api")
public class SkillsController {
    private final SkillWorkspaceService skills;
    private final AgentStore agents;
    private final SkillZipImportService zipImport;
    private final SkillDependencyService dependencies;
    private final SkillHubSearchService hubSearch;
    private final SkillHubInstallService hubInstall;
    private final ObjectMapper mapper;

    public SkillsController(
        SkillWorkspaceService skills,
        AgentStore agents,
        SkillZipImportService zipImport, SkillDependencyService dependencies,
        SkillHubSearchService hubSearch, SkillHubInstallService hubInstall,
        ObjectMapper mapper
    ) {
        this.skills = skills;
        this.agents = agents;
        this.zipImport = zipImport;
        this.dependencies = dependencies;
        this.hubSearch = hubSearch;
        this.hubInstall = hubInstall;
        this.mapper = mapper;
    }

    @GetMapping({"/skills", "/agents/{agentId}/skills"})
    public List<Map<String, Object>> list(
        @PathVariable(required = false) String agentId,
        HttpServletRequest request
    ) {
        return skills.list(workspace(agentId, request));
    }

    @PostMapping({"/skills/refresh", "/agents/{agentId}/skills/refresh"})
    public List<Map<String, Object>> refresh(
        @PathVariable(required = false) String agentId,
        HttpServletRequest request
    ) {
        skills.reconcile(workspace(agentId, request));
        return list(agentId, request);
    }

    @GetMapping({"/skills/hub/search", "/agents/{agentId}/skills/hub/search"})
    public List<Map<String, Object>> searchHub(@PathVariable(required = false) String agentId, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "20") int limit) {
        return hubSearch.search(q, limit);
    }

    @PostMapping({
        "/skills/hub/install/start",
        "/agents/{agentId}/skills/hub/install/start"
    })
    public Map<String, Object> startHubInstall(
        @PathVariable(required = false) String agentId,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        var task = hubInstall.start(
            workspace(agentId, request), text(body.get("bundle_url")),
            text(body.get("version")), Boolean.TRUE.equals(body.get("enable")),
            text(body.get("target_name"))
        );
        return task(task);
    }

    @GetMapping({
        "/skills/hub/install/status/{taskId}",
        "/agents/{agentId}/skills/hub/install/status/{taskId}"
    })
    public Map<String, Object> hubInstallStatus(
        @PathVariable(required = false) String agentId,
        @PathVariable String taskId
    ) {
        return task(hubInstall.status(taskId));
    }

    @PostMapping({
        "/skills/hub/install/cancel/{taskId}",
        "/agents/{agentId}/skills/hub/install/cancel/{taskId}"
    })
    public Map<String, Object> cancelHubInstall(
        @PathVariable(required = false) String agentId,
        @PathVariable String taskId
    ) {
        return Map.of("task_id", taskId, "status", hubInstall.cancel(taskId));
    }

    @PostMapping({"/skills", "/agents/{agentId}/skills"})
    public Map<String, Object> create(
        @PathVariable(required = false) String agentId,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> result = skills.create(
            workspace(agentId, request),
            text(body.get("name")),
            text(body.get("content")),
            asMap(body.get("references")),
            asMap(body.get("scripts")),
            asMap(body.get("extra_files")),
            asMap(body.get("config")),
            !Boolean.FALSE.equals(body.get("enable"))
        );
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Skill already exists"
            );
        }
        return Map.of(
            "created", true,
            "name", result.get("name")
        );
    }

    @PostMapping({
        "/skills/upload",
        "/agents/{agentId}/skills/upload"
    })
    public ResponseEntity<Map<String, Object>> upload(
        @PathVariable(required = false) String agentId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(defaultValue = "true") boolean enable,
        @RequestParam(defaultValue = "") String target_name,
        @RequestParam(defaultValue = "") String rename_map,
        HttpServletRequest request
    ) throws java.io.IOException {
        Map<String, String> renames;
        try {
            renames = rename_map.isBlank() ? Map.of()
                : mapper.readValue(rename_map,
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "rename_map must be a JSON object", exception
            );
        }
        Map<String, Object> result = zipImport.importZip(
            workspace(agentId, request), file.getBytes(),
            enable, target_name, renames
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
            (List<Map<String, Object>>) result.get("conflicts");
        return ResponseEntity.status(conflicts.isEmpty()
            ? HttpStatus.OK : HttpStatus.CONFLICT).body(result);
    }

    @PutMapping("/skills/save")
    public Map<String, Object> save(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return saveFor(null, body, request);
    }

    @PostMapping("/agents/{agentId}/skills/save")
    public Map<String, Object> saveForAgent(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return saveFor(agentId, body, request);
    }

    private Map<String, Object> saveFor(
        String agentId,
        Map<String, Object> body,
        HttpServletRequest request
    ) {
        String source = text(body.getOrDefault(
            "source_name", body.get("name")
        ));
        Map<String, Object> result = skills.save(
            workspace(agentId, request), source,
            text(body.get("name")), text(body.get("content")),
            asMap(body.get("config")),
            Boolean.TRUE.equals(body.get("overwrite"))
        );
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String reason = text(result.get("reason"));
            throw new ResponseStatusException(
                "conflict".equals(reason)
                    ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                reason
            );
        }
        return result;
    }

    @PostMapping({
        "/skills/{name}/enable",
        "/agents/{agentId}/skills/{name}/enable"
    })
    public Map<String, Object> enable(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        HttpServletRequest request
    ) {
        Path workspace = workspace(agentId, request);
        if (!skills.setEnabled(workspace, name, true)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Skill not found"
            );
        }
        return Map.of("enabled", true, "success", true,
            "updated_workspaces", List.of(
                workspace.getFileName().toString()
            ));
    }

    @PostMapping({
        "/skills/{name}/disable",
        "/agents/{agentId}/skills/{name}/disable"
    })
    public Map<String, Object> disable(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        HttpServletRequest request
    ) {
        if (!skills.setEnabled(workspace(agentId, request),
            name, false)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Skill not found"
            );
        }
        return Map.of("disabled", true, "success", true);
    }

    @DeleteMapping({
        "/skills/{name}",
        "/agents/{agentId}/skills/{name}"
    })
    public Map<String, Object> delete(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        HttpServletRequest request
    ) {
        if (!skills.delete(workspace(agentId, request), name)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only disabled skills can be deleted"
            );
        }
        return Map.of("deleted", true);
    }

    @PutMapping({
        "/skills/{name}/channels",
        "/agents/{agentId}/skills/{name}/channels"
    })
    public Map<String, Object> channels(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        @RequestBody List<String> channels,
        HttpServletRequest request
    ) {
        require(skills.setChannels(workspace(agentId, request),
            name, channels));
        return Map.of("updated", true, "channels", channels);
    }

    @PutMapping({
        "/skills/{name}/preload",
        "/agents/{agentId}/skills/{name}/preload"
    })
    public Map<String, Object> preload(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        boolean preload = Boolean.TRUE.equals(body.get("preload"));
        require(skills.setPreload(workspace(agentId, request),
            name, preload));
        return Map.of("updated", true, "preload", preload);
    }

    @PutMapping({
        "/skills/{name}/tags",
        "/agents/{agentId}/skills/{name}/tags"
    })
    public Map<String, Object> tags(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        @RequestBody List<String> tags,
        HttpServletRequest request
    ) {
        require(skills.setTags(workspace(agentId, request),
            name, tags));
        return Map.of("updated", true, "tags", tags);
    }

    @GetMapping({
        "/skills/{name}/config",
        "/agents/{agentId}/skills/{name}/config"
    })
    public Map<String, Object> config(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        HttpServletRequest request
    ) {
        return skills.config(workspace(agentId, request), name);
    }

    @GetMapping({
        "/skills/{name}/files/{*filePath}",
        "/agents/{agentId}/skills/{name}/files/{*filePath}"
    })
    public Map<String, String> file(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        @PathVariable String filePath,
        HttpServletRequest request
    ) {
        String relative = filePath.startsWith("/")
            ? filePath.substring(1) : filePath;
        return Map.of("content", skills.loadFile(
            workspace(agentId, request), name, relative
        ));
    }

    @GetMapping({"/skills/{name}/dependencies", "/agents/{agentId}/skills/{name}/dependencies"})
    public Map<String, Object> dependencies(@PathVariable(required = false) String agentId, @PathVariable String name, HttpServletRequest request) {
        Path root = workspace(agentId, request);
        String content = skills.list(root).stream().filter(skill -> name.equals(skill.get("name"))).map(skill -> String.valueOf(skill.get("content"))).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found"));
        return dependencies.check(root, name, content);
    }

    @PutMapping({
        "/skills/{name}/config",
        "/agents/{agentId}/skills/{name}/config"
    })
    public Map<String, Object> setConfig(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        require(skills.setConfig(workspace(agentId, request),
            name, asMap(body.get("config"))));
        return Map.of("updated", true);
    }

    @DeleteMapping({
        "/skills/{name}/config",
        "/agents/{agentId}/skills/{name}/config"
    })
    public Map<String, Object> clearConfig(
        @PathVariable(required = false) String agentId,
        @PathVariable String name,
        HttpServletRequest request
    ) {
        require(skills.setConfig(workspace(agentId, request),
            name, Map.of()));
        return Map.of("cleared", true);
    }

    private Path workspace(String explicit, HttpServletRequest request) {
        String agentId = explicit;
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getHeader("X-Agent-Id");
        }
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        if (agentId == null || agentId.isBlank()) {
            agentId = AgentStore.DEFAULT_AGENT_ID;
        }
        return agents.workspaceDirForAgent(agentId);
    }

    private static void require(boolean updated) {
        if (!updated) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Skill not found"
            );
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> result.put(
            String.valueOf(key), value
        ));
        return result;
    }

    private static Map<String, Object> task(SkillHubTaskService.Task task) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("task_id", task.taskId());
        response.put("bundle_url", task.bundleUrl());
        response.put("version", task.version());
        response.put("enable", task.enable());
        response.put("status", task.status());
        response.put("error", task.error());
        response.put("result", task.result());
        response.put("created_at", task.createdAt());
        response.put("updated_at", task.updatedAt());
        return response;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(
        IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(Map.of(
            "detail", exception.getMessage()
        ));
    }
}
