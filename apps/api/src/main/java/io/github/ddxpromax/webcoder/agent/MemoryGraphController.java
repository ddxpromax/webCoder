package io.github.ddxpromax.webcoder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo memory reindex and wikilink graph endpoints. */
@RestController
@RequestMapping("/api/agents/{agentId}/memory")
@CrossOrigin(origins = "*")
public class MemoryGraphController {
    private static final Pattern WIKILINK = Pattern.compile(
        "\\[\\[([^\\]\\|#]+)(?:#[^\\]]*)?(?:\\|([^\\]]*))?\\]\\]"
    );
    private final AgentStore agents;
    private final MemoryBackendRegistry backends;

    public MemoryGraphController(
        AgentStore agents,
        MemoryBackendRegistry backends
    ) {
        this.agents = agents;
        this.backends = backends;
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex(
        @PathVariable String agentId
    ) {
        requireAgent(agentId);
        MemoryBackend backend = backends.resolve(agentId);
        return backend == null
            ? Map.of("status", "no_backend")
            : backend.rebuild();
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(
        @PathVariable String agentId
    ) {
        requireAgent(agentId);
        return buildGraph(agents.workspaceDirForAgent(agentId)
            .resolve("memory"));
    }

    static Map<String, Object> buildGraph(Path memoryDirectory) {
        record Node(
            String id,
            String path,
            String name,
            String description,
            boolean indexed,
            boolean virtual,
            String section
        ) {}
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Map<String, String>> edges = new ArrayList<>();
        if (!Files.isDirectory(memoryDirectory)) {
            return Map.of(
                "version", 1,
                "nodes", List.of(),
                "edges", List.of()
            );
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(memoryDirectory)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString()
                    .endsWith(".md"))
                .sorted().forEach(files::add);
        } catch (IOException ignored) {
            // Return the files collected before an unreadable directory.
        }
        Map<String, Path> byRelative = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = memoryDirectory.relativize(file)
                .toString().replace('\\', '/');
            String section = relative.startsWith("digest/")
                ? "digest" : "daily";
            byRelative.put(relative.toLowerCase(), file);
            String description = description(file);
            String id = "file:" + relative.toLowerCase();
            nodes.put(id, new Node(
                id, relative, file.getFileName().toString(),
                description, true, false, section
            ));
            String[] parts = relative.split("/");
            if (parts.length >= 2 && "digest".equals(parts[0])) {
                String rootId = "root:digest/" + parts[1];
                nodes.putIfAbsent(rootId, new Node(
                    rootId, parts[1], parts[1], "",
                    false, false, "digest"
                ));
            }
        }

        for (Path file : files) {
            String relative = memoryDirectory.relativize(file)
                .toString().replace('\\', '/');
            String sourceId = "file:" + relative.toLowerCase();
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException ignored) {
                continue;
            }
            var matcher = WIKILINK.matcher(content);
            Set<String> targets = new LinkedHashSet<>();
            while (matcher.find()) {
                String target = matcher.group(1).trim().toLowerCase();
                if (!target.isBlank()) {
                    targets.add(target);
                }
            }
            for (String target : targets) {
                String targetPath = target.endsWith(".md")
                    ? target : target + ".md";
                String targetId = byRelative.containsKey(targetPath)
                    ? "file:" + targetPath
                    : "unresolved:" + target;
                nodes.putIfAbsent(targetId, new Node(
                    targetId, target, target, "",
                    false, true, null
                ));
                edges.add(Map.of(
                    "source", sourceId,
                    "target", targetId
                ));
            }
        }
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (Node node : nodes.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.id());
            item.put("path", node.path());
            item.put("name", node.name());
            item.put("description", node.description());
            item.put("indexed", node.indexed());
            item.put("virtual", node.virtual());
            item.put("section", node.section());
            nodeList.add(item);
        }
        return Map.of(
            "version", 1,
            "nodes", nodeList,
            "edges", edges
        );
    }

    private void requireAgent(String agentId) {
        if (agents.getProfile(agentId) == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
    }

    private static String description(Path file) {
        try {
            String text = Files.readString(file).lines()
                .filter(line -> !line.isBlank()
                    && !line.startsWith("#")
                    && !line.startsWith("---"))
                .findFirst().orElse("").trim();
            return text.length() > 120
                ? text.substring(0, 117) + "..." : text;
        } catch (IOException ignored) {
            return "";
        }
    }
}
