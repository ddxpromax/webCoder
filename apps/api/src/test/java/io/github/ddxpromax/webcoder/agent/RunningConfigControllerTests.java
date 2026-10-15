package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.PreCallEvent;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RunningConfigControllerTests {
    @TempDir Path directory;

    @Test
    void mergesMajoDefaultsAndPersistsOverrides() throws Exception {
        AgentStore agents = store();
        MemoryBackendRegistry backends = registry(agents);
        var mvc = MockMvcBuilders.standaloneSetup(
            new RunningConfigController(agents, backends)
        ).build();

        mvc.perform(get("/api/workspace/running-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max_iters").value(100))
            .andExpect(jsonPath("$.light_context_config.context_compact_config.enabled")
                .value(true))
            .andExpect(jsonPath("$.approval_level").value("AUTO"));

        mvc.perform(put("/api/workspace/running-config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "max_iters": 60,
                      "approval_level": "smart",
                      "light_context_config": {
                        "context_compact_config": {
                          "compact_threshold_ratio": 0.65
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.max_iters").value(60))
            .andExpect(jsonPath("$.approval_level").value("SMART"))
            .andExpect(jsonPath("$.light_context_config.context_compact_config.enabled")
                .value(true))
            .andExpect(jsonPath("$.light_context_config.context_compact_config.compact_threshold_ratio")
                .value(0.65));

        assertThat(agents.getRunningConfig("default"))
            .containsEntry("max_iters", 60);
        assertThat(agents.getApprovalLevel("default"))
            .isEqualTo("SMART");
    }

    @Test
    void memoryBackendSwitchTakesEffectWithoutRestart() {
        AgentStore agents = store();
        MemoryBackendRegistry backends = registry(agents);
        RunningConfigController controller =
            new RunningConfigController(agents, backends);

        controller.updateFor("default", java.util.Map.of(
            "memory_manager_backend", "keyword"
        ));
        assertThat(backends.resolve("default").id())
            .isEqualTo("keyword");

        controller.updateFor("default", java.util.Map.of(
            "memory_manager_backend", "summary"
        ));
        assertThat(backends.resolve("default").id())
            .isEqualTo("summary");

        controller.updateFor("default", java.util.Map.of(
            "memory_manager_backend", "missing"
        ));
        assertThat(backends.resolve("default").id())
            .isEqualTo("keyword");
    }

    @Test
    void codingModePersistsAndInjectsPromptOnPreCall() throws Exception {
        AgentStore agents = store();
        CodingModeService service = new CodingModeService(agents);
        var mvc = MockMvcBuilders.standaloneSetup(
            new CodingModeController(agents)
        ).build();

        mvc.perform(get("/api/coding-mode"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/coding-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));

        assertThat(service.isEnabled("default")).isTrue();
        PreCallEvent event = mock(PreCallEvent.class);
        when(event.getSystemMessage()).thenReturn(null);
        new CodingModePromptInjector(
            service, "default", directory
        ).onEvent(event).block();
        verify(event).appendSystemContent(
            org.mockito.ArgumentMatchers.contains(
                "# Coding Mode"
            )
        );
    }

    private AgentStore store() {
        return new AgentStore(
            new MajoWorkingDirectory(directory.toString()),
            new JsonFileStore(new ObjectMapper())
        );
    }

    private MemoryBackendRegistry registry(AgentStore agents) {
        return new MemoryBackendRegistry(
            agents,
            mock(MemorySummarizer.class),
            mock(ModelRoutingService.class),
            mock(SettingsService.class),
            mock(EmbeddingClient.class)
        );
    }
}
