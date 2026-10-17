package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolConfigServiceTests {
    @TempDir
    Path directory;

    @Test
    void persistedToggleBlocksTheUnderlyingSdkToolCall()
        throws Exception {
        AgentStore agents = agents();
        ToolConfigService tools = new ToolConfigService(agents);
        var mvc = MockMvcBuilders.standaloneSetup(
            new ToolsController(tools)
        ).build();

        mvc.perform(get("/api/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name")
                .value("read_file"));
        mvc.perform(patch("/api/tools/grep_search/toggle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled")
                .value(false));
        assertThat(new ToolConfigService(agents)
            .isEnabled("default", "search_code"))
            .isFalse();

        ApprovalHook hook = new ApprovalHook(
            "default", agents, new ApprovalStore(),
            new ToolGuardService(agents),
            new FileGuardService(agents), tools,
            directory
        );
        PreActingEvent event = new PreActingEvent(
            mock(Agent.class), new Toolkit(),
            new ToolUseBlock(
                "tool-1", "search_code",
                Map.of("pattern", "class")
            )
        );
        hook.onEvent(event).block();
        assertThat(event.getToolUse().getState())
            .isEqualTo(ToolCallState.FINISHED);
        assertThat(event.getToolUse().getContent())
            .contains("disabled");
    }

    @Test
    void exposesFindSymbolSeparatelyFromMajoAstSearch() {
        ToolConfigService tools = new ToolConfigService(agents());

        assertThat(tools.find("default", "find_symbol"))
            .containsEntry("name", "find_symbol");
        assertThat(tools.isEnabled("default", "find_symbol")).isTrue();
        assertThat(tools.find("default", "ast_search"))
            .containsEntry("name", "ast_search");
    }

    private AgentStore agents() {
        return new AgentStore(
            new MajoWorkingDirectory(directory.toString()),
            new JsonFileStore(new ObjectMapper())
        );
    }
}
