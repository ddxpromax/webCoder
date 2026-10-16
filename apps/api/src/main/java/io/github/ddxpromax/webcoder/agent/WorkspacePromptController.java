package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class WorkspacePromptController {
    private static final List<String> PROMPT_FILES = List.of(
        "AGENTS.md", "SOUL.md", "PROFILE.md"
    );
    private final AgentStore agents;

    public WorkspacePromptController(AgentStore agents) {
        this.agents = agents;
    }

    @GetMapping("/workspace/system-prompt-files")
    public List<String> files(HttpServletRequest request) {
        return filesFor(agentId(request));
    }

    @GetMapping("/agents/{agentId}/workspace/system-prompt-files")
    public List<String> filesFor(@PathVariable String agentId) {
        Path workspace = agents.workspaceDirForAgent(agentId);
        return PROMPT_FILES.stream()
            .filter(name -> Files.isRegularFile(workspace.resolve(name)))
            .toList();
    }

    @PutMapping("/workspace/system-prompt-files")
    public List<String> update(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        return updateFor(agentId(request), body);
    }

    @PutMapping("/agents/{agentId}/workspace/system-prompt-files")
    public List<String> updateFor(
        @PathVariable String agentId,
        @RequestBody Map<String, Object> body
    ) {
        if (!(body.get("files") instanceof List<?> files)) {
            return filesFor(agentId);
        }
        List<String> result = new ArrayList<>();
        for (Object value : files) {
            String name = String.valueOf(value);
            if (!name.contains("/") && !name.contains("\\")
                && name.endsWith(".md")) {
                result.add(name);
            }
        }
        return result;
    }

    @GetMapping("/workspace/commands/available")
    public Map<String, Object> commands() {
        List<Map<String, Object>> values = List.of(
            command("/clear", "Clear the conversation context", false),
            command("/compact", "Compact the conversation context", true),
            command("/new", "Start a new session", false),
            command("/status", "Show agent and session status", false),
            command("/stop", "Stop the current run", false)
        );
        return Map.of("commands", values);
    }

    private static Map<String, Object> command(
        String name,
        String description,
        boolean arguments
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", name);
        result.put("description", description);
        result.put("accepts_arguments", arguments);
        return result;
    }

    private static String agentId(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Id");
        return header == null || header.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : header;
    }
}
