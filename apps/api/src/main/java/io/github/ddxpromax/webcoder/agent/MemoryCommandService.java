package io.github.ddxpromax.webcoder.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Majo's /memory command surface over the selected backend. */
@Service
public class MemoryCommandService {
    private static final int MAX_READ_CHARS = 8000;
    private final AgentStore agents;
    private final MemoryBackendRegistry registry;

    public MemoryCommandService(
        AgentStore agents,
        MemoryBackendRegistry registry
    ) {
        this.agents = agents;
        this.registry = registry;
    }

    public String execute(String agentId, String rawArgs) {
        String args = rawArgs == null ? "" : rawArgs.strip();
        String[] parts = args.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";
        return switch (command) {
            case "", "status", "help" -> status(agentId);
            case "list" -> list(agentId, rest);
            case "search" -> search(agentId, rest);
            case "read" -> read(agentId, rest);
            case "forget" -> forget(agentId, rest);
            case "write" -> write(agentId, rest);
            default -> "Unknown memory command: " + command
                + "\n\n" + status(agentId);
        };
    }

    private String status(String agentId) {
        MemoryBackend backend = registry.resolve(agentId);
        StringBuilder reply = new StringBuilder("**Memory status**\n")
            .append("- Configured backend: `")
            .append(registry.configuredBackendId(agentId))
            .append("`\n- Registered backends: ")
            .append(String.join(", ", registry.backendIds()))
            .append("\n");
        if (backend == null) {
            reply.append("- Availability: unavailable\n");
        } else {
            reply.append("- Active backend: `")
                .append(backend.id())
                .append("`\n- Availability: available\n");
        }
        reply.append("- Automatic writes: ")
            .append(autoMemoryEnabled(agentId)
                ? "enabled" : "disabled")
            .append("\n\nCommands: `status` / `list [n]` / ")
            .append("`search <terms>` / `read <path>` / ")
            .append("`forget <path>` / `write <content>`");
        return reply.toString();
    }

    private String list(String agentId, String rawLimit) {
        Path root = memoryDirectory(agentId);
        if (root == null) {
            return "Agent workspace is unavailable.";
        }
        int limit = 10;
        try {
            if (!rawLimit.isBlank()) {
                limit = Math.max(1, Integer.parseInt(rawLimit));
            }
        } catch (NumberFormatException ignored) {
            // Majo keeps the default limit for invalid input.
        }
        List<Path> notes = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                        .toLowerCase().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString()
                        .startsWith("."))
                    .forEach(notes::add);
            } catch (IOException exception) {
                return "Cannot list memories: "
                    + exception.getMessage();
            }
        }
        if (notes.isEmpty()) {
            return "Memory is empty. Use `/memory write <content>` to add a note.";
        }
        notes.sort(Comparator.comparingLong(
            this::modifiedAt).reversed());
        StringBuilder reply = new StringBuilder()
            .append(notes.size()).append(" memory files; latest ")
            .append(Math.min(limit, notes.size())).append(":\n");
        for (int i = 0; i < Math.min(limit, notes.size()); i++) {
            Path note = notes.get(i);
            reply.append("- `memory/")
                .append(root.relativize(note).toString()
                    .replace('\\', '/'))
                .append("`\n");
        }
        return reply.toString();
    }

    private String search(String agentId, String query) {
        if (query.isBlank()) {
            return "Usage: `/memory search <terms>`";
        }
        MemoryBackend backend = registry.resolve(agentId);
        if (backend == null) {
            return "Memory backend is unavailable.";
        }
        List<MemoryHit> hits = backend.search(query, 5);
        if (hits.isEmpty()) {
            return "No memories match \"" + query + "\".";
        }
        StringBuilder reply = new StringBuilder()
            .append(hits.size()).append(" matching memories:\n");
        for (MemoryHit hit : hits) {
            reply.append("- `").append(hit.source()).append("`");
            if (!hit.snippet().isBlank()) {
                reply.append(": ").append(hit.snippet().length() > 200
                    ? hit.snippet().substring(0, 200) + "..."
                    : hit.snippet());
            }
            reply.append('\n');
        }
        return reply.append("Use `/memory read <path>` for the full note.")
            .toString();
    }

    private String read(String agentId, String path) {
        Path note = resolveMemoryPath(agentId, path);
        if (note == null) {
            return "Usage: `/memory read <path>` (path must be inside memory/).";
        }
        try {
            String content = Files.readString(
                note, StandardCharsets.UTF_8
            );
            if (content.length() > MAX_READ_CHARS) {
                content = content.substring(0, MAX_READ_CHARS)
                    + "\n...[content truncated]";
            }
            return "**" + path + "**\n\n" + content;
        } catch (IOException exception) {
            return "Cannot read memory: " + exception.getMessage();
        }
    }

    private String forget(String agentId, String path) {
        Path note = resolveMemoryPath(agentId, path);
        if (note == null) {
            return "Usage: `/memory forget <path>` (path must be inside memory/).";
        }
        try {
            Files.deleteIfExists(note);
            MemoryBackend backend = registry.resolve(agentId);
            if (backend instanceof KeywordMemoryBackend keyword) {
                keyword.rebuildForPath(agents.workspaceDirForAgent(agentId));
            }
            return "Deleted `" + path + "` and rebuilt the memory index.";
        } catch (IOException exception) {
            return "Cannot delete memory: " + exception.getMessage();
        }
    }

    private String write(String agentId, String content) {
        if (content.isBlank()) {
            return "Usage: `/memory write <content>`";
        }
        MemoryBackend backend = registry.resolve(agentId);
        if (backend == null) {
            return "Memory backend is unavailable.";
        }
        backend.remember(content, Map.of(
            "agent_id", agentId,
            "trigger", "manual",
            "source", "/memory"
        ));
        return "Saved to long-term memory using `"
            + backend.id() + "`.";
    }

    private Path memoryDirectory(String agentId) {
        try {
            return agents.workspaceDirForAgent(agentId)
                .resolve("memory").normalize();
        } catch (Exception exception) {
            return null;
        }
    }

    private Path resolveMemoryPath(String agentId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path root = memoryDirectory(agentId);
        if (root == null) {
            return null;
        }
        String relative = value.strip().replace('\\', '/');
        if (relative.startsWith("memory/")) {
            relative = relative.substring("memory/".length());
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            return target.toRealPath().startsWith(root.toRealPath())
                ? target : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean autoMemoryEnabled(String agentId) {
        Object config = registry.memoryConfig(agentId)
            .get("auto_memory_config");
        return config instanceof Map<?, ?> map
            && Boolean.TRUE.equals(map.get("enabled"));
    }

    private long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException exception) {
            return 0;
        }
    }
}
