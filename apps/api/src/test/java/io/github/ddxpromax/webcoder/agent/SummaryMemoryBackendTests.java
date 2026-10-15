package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryMemoryBackendTests {
    @TempDir
    Path workspace;

    @Test
    void writesExtractedFactsAndFallsBackToRawExcerpt()
        throws Exception {
        MemorySummarizer summarizer = mock(MemorySummarizer.class);
        when(summarizer.summarize("default", "Long conversation"))
            .thenReturn(Optional.of("Use MySQL for persistence."));
        when(summarizer.summarize("default", "Unclear conversation"))
            .thenReturn(Optional.empty());
        SummaryMemoryBackend backend = new SummaryMemoryBackend(
            summarizer
        );
        backend.start(new MemoryBackendContext(
            "default", workspace, Map.of(), "en"
        ));

        backend.remember("Long conversation",
            Map.of("agent_id", "default"));
        backend.remember("Unclear conversation",
            Map.of("agent_id", "default"));

        Path daily = workspace.resolve("memory/daily")
            .resolve(java.time.LocalDate.now() + ".md");
        String content = Files.readString(daily);
        assertThat(content)
            .contains("Use MySQL for persistence.")
            .contains("Unclear conversation")
            .doesNotContain("Long conversation");
        assertThat(backend.search("mysql", 5)).hasSize(1);
    }
}
