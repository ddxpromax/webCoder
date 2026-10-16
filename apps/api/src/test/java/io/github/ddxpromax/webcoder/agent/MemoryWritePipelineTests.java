package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryWritePipelineTests {
    @TempDir
    Path directory;

    @Test
    void writesAfterConfiguredIntervalAndPreservesTurnPairing()
        throws Exception {
        AgentStore agents = agentStore();
        JsonFileStore files = new JsonFileStore(new ObjectMapper());
        MemoryBackendRegistry registry = new MemoryBackendRegistry(
            agents,
            org.mockito.Mockito.mock(MemorySummarizer.class),
            org.mockito.Mockito.mock(io.github.ddxpromax.webcoder.model.ModelRoutingService.class),
            org.mockito.Mockito.mock(io.github.ddxpromax.webcoder.settings.SettingsService.class),
            org.mockito.Mockito.mock(EmbeddingClient.class)
        );
        MemoryWritePipeline pipeline = new MemoryWritePipeline(
            agents, registry, files
        );

        String first = pipeline.beginTurn("default", "First question");
        String second = pipeline.beginTurn("default", "Second question");
        pipeline.finishTurn("default", second, "Second answer");
        assertThat(registry.resolve("default")
            .search("second", 5)).isEmpty();
        pipeline.finishTurn("default", first, "First answer");

        Path daily = agents.defaultWorkspaceDir()
            .resolve("memory/daily")
            .resolve(java.time.LocalDate.now() + ".md");
        assertThat(daily).exists();
        assertThat(Files.readString(daily))
            .contains("User: First question\nAssistant: First answer")
            .contains("User: Second question\nAssistant: Second answer");
        assertThat(files.read(agents.defaultWorkspaceDir()
            .resolve("memory/.auto_memory_state.json"), Map.of()))
            .containsEntry("pending", java.util.List.of());
    }

    private AgentStore agentStore() {
        JsonFileStore files = new JsonFileStore(new ObjectMapper());
        AgentStore agents = new AgentStore(
            new MajoWorkingDirectory(directory.toString()), files
        );
        agents.saveRunningConfig("default", Map.of(
            "reme_light_memory_config", Map.of(
                "auto_memory_config", Map.of(
                    "enabled", true,
                    "interval", 2
                )
            )
        ), null);
        return agents;
    }
}
