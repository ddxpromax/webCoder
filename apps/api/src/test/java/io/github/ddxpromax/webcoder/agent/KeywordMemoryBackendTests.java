package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeywordMemoryBackendTests {
    @TempDir
    Path workspace;

    @Test
    void searchesExistingNotesAndRefreshesAfterExternalEdit()
        throws Exception {
        Path note = workspace.resolve("memory/project.md");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "Repository uses MySQL transactions.");
        KeywordMemoryBackend backend = new KeywordMemoryBackend();
        backend.start(new MemoryBackendContext(
            "default", workspace, Map.of(), "en"
        ));

        assertThat(backend.search("mysql", 5))
            .extracting(MemoryHit::source)
            .containsExactly("memory/project.md");

        Files.writeString(note,
            "Repository uses PostgreSQL transactions now.");
        assertThat(backend.search("mysql", 5)).isEmpty();
        assertThat(backend.search("postgresql", 5))
            .extracting(MemoryHit::source)
            .containsExactly("memory/project.md");
    }

    @Test
    void rememberWritesDailyNoteAndMakesItSearchable() {
        KeywordMemoryBackend backend = new KeywordMemoryBackend();
        backend.start(new MemoryBackendContext(
            "default", workspace, Map.of(), "en"
        ));

        backend.remember("Prefer concise explanations about indexing.",
            Map.of("agent_id", "default", "trigger", "manual"));

        assertThat(backend.search("indexing", 5))
            .extracting(MemoryHit::source)
            .anyMatch(source -> source.startsWith("memory/daily/"));
        assertThat(workspace.resolve("memory/.index.json"))
            .exists();
    }
}
