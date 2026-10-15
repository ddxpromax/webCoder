package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryGraphControllerTests {
    @TempDir
    Path directory;

    @Test
    void graphIncludesDigestRootsResolvedLinksAndMissingTargets()
        throws Exception {
        Path memory = directory.resolve("memory");
        Files.createDirectories(memory.resolve("digest/project"));
        Files.writeString(memory.resolve("digest/project/overview.md"),
            "# Project\nUses MySQL.\nSee [[daily/notes]] and [[missing|other]].");
        Files.createDirectories(memory.resolve("daily"));
        Files.writeString(memory.resolve("daily/notes.md"),
            "# Notes\nUse indexes.");

        Map<String, Object> graph = MemoryGraphController.buildGraph(
            memory
        );

        assertThat(graph).containsEntry("version", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes =
            (List<Map<String, Object>>) graph.get("nodes");
        assertThat(nodes).extracting(node -> node.get("id"))
            .contains("file:digest/project/overview.md",
                "file:daily/notes.md", "root:digest/project",
                "unresolved:missing");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> edges =
            (List<Map<String, String>>) graph.get("edges");
        assertThat(edges).contains(
            Map.of(
                "source", "file:digest/project/overview.md",
                "target", "file:daily/notes.md"
            ),
            Map.of(
                "source", "file:digest/project/overview.md",
                "target", "unresolved:missing"
            )
        );
    }
}
