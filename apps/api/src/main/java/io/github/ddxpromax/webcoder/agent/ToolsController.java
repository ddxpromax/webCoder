package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo's per-agent built-in tool settings API. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ToolsController {
    private final ToolConfigService tools;

    public ToolsController(ToolConfigService tools) {
        this.tools = tools;
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> list(
        HttpServletRequest request
    ) {
        return tools.list(agentId(request));
    }

    @GetMapping("/agents/{agentId}/tools")
    public List<Map<String, Object>> agentList(
        @PathVariable String agentId
    ) {
        return tools.list(agentId);
    }

    @PatchMapping("/tools/{toolName}/toggle")
    public ResponseEntity<?> toggle(
        @PathVariable String toolName,
        HttpServletRequest request
    ) {
        return toggleFor(agentId(request), toolName);
    }

    @PatchMapping("/agents/{agentId}/tools/{toolName}/toggle")
    public ResponseEntity<?> agentToggle(
        @PathVariable String agentId,
        @PathVariable String toolName
    ) {
        return toggleFor(agentId, toolName);
    }

    @PatchMapping("/tools/{toolName}/async-execution")
    public ResponseEntity<?> asyncExecution(
        @PathVariable String toolName,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return asyncFor(agentId(request), toolName, body);
    }

    @PatchMapping("/agents/{agentId}/tools/{toolName}/async-execution")
    public ResponseEntity<?> agentAsyncExecution(
        @PathVariable String agentId,
        @PathVariable String toolName,
        @RequestBody Map<String, Object> body
    ) {
        return asyncFor(agentId, toolName, body);
    }

    @GetMapping("/tools/{toolName}/config")
    public ResponseEntity<?> config(
        @PathVariable String toolName,
        HttpServletRequest request
    ) {
        return configFor(agentId(request), toolName);
    }

    @GetMapping("/agents/{agentId}/tools/{toolName}/config")
    public ResponseEntity<?> agentConfig(
        @PathVariable String agentId,
        @PathVariable String toolName
    ) {
        return configFor(agentId, toolName);
    }

    @PostMapping("/tools/{toolName}/config")
    public ResponseEntity<?> updateConfig(
        @PathVariable String toolName,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return updateConfigFor(agentId(request), toolName, body);
    }

    @PostMapping("/agents/{agentId}/tools/{toolName}/config")
    public ResponseEntity<?> agentUpdateConfig(
        @PathVariable String agentId,
        @PathVariable String toolName,
        @RequestBody Map<String, Object> body
    ) {
        return updateConfigFor(agentId, toolName, body);
    }

    private ResponseEntity<?> toggleFor(
        String agentId,
        String toolName
    ) {
        Map<String, Object> result = tools.toggle(agentId, toolName);
        return result == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(result);
    }

    private ResponseEntity<?> asyncFor(
        String agentId,
        String toolName,
        Map<String, Object> body
    ) {
        Map<String, Object> result = tools.setAsyncExecution(
            agentId, toolName,
            Boolean.TRUE.equals(body.get("async_execution"))
        );
        return result == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(result);
    }

    private ResponseEntity<?> configFor(
        String agentId,
        String toolName
    ) {
        Map<String, Object> tool = tools.find(agentId, toolName);
        return tool == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(tool.get("config"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<?> updateConfigFor(
        String agentId,
        String toolName,
        Map<String, Object> body
    ) {
        Object raw = body.get("config");
        Map<String, Object> config = raw instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        if (!tools.setConfig(agentId, toolName, config)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Configuration updated"
        ));
    }

    private static String agentId(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String param = request.getParameter("agent");
        return param == null || param.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : param;
    }
}
