package io.github.ddxpromax.webcoder.agent;

import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compatibility endpoint for Majo's local memory command runner. */
@RestController
@RequestMapping("/api/commands")
@CrossOrigin(origins = "*")
public class MemoryCommandsController {
    private final MemoryCommandService commands;

    public MemoryCommandsController(MemoryCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/run")
    public Map<String, Object> run(
        @RequestBody Map<String, Object> body
    ) {
        String input = String.valueOf(
            body.getOrDefault("text", "")
        ).strip();
        String agentId = String.valueOf(
            body.getOrDefault("agent_id", "default")
        ).strip();
        if (agentId.isBlank()) {
            agentId = AgentStore.DEFAULT_AGENT_ID;
        }
        if (!input.equalsIgnoreCase("/memory")
            && !input.toLowerCase().startsWith("/memory ")) {
            return Map.of("reply", "Only /memory commands are supported.");
        }
        return Map.of("reply", commands.execute(
            agentId, input.substring("/memory".length())
        ));
    }
}
