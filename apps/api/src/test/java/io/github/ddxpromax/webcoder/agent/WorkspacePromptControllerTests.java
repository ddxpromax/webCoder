package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.PreCallEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspacePromptControllerTests {
    @TempDir Path directory;

    @Test
    void listsAndInjectsWorkspacePromptFiles() throws Exception {
        AgentStore agents = new AgentStore(
            new MajoWorkingDirectory(directory.toString()),
            new JsonFileStore(new ObjectMapper())
        );
        Path workspace = agents.workspaceDirForAgent("default");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"),
            "Review changes before finishing.");
        Files.writeString(workspace.resolve("SOUL.md"),
            "Explain decisions clearly.");

        var mvc = MockMvcBuilders.standaloneSetup(
            new WorkspacePromptController(agents)
        ).build();
        mvc.perform(get("/api/workspace/system-prompt-files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("AGENTS.md"))
            .andExpect(jsonPath("$[1]").value("SOUL.md"));
        mvc.perform(get("/api/workspace/commands/available"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commands[1].command")
                .value("/compact"));

        PreCallEvent event = mock(PreCallEvent.class);
        when(event.getSystemMessage()).thenReturn(null);
        new SystemPromptFilesHook(workspace).onEvent(event).block();
        verify(event).appendSystemContent(
            org.mockito.ArgumentMatchers.contains(
                "Review changes before finishing."
            )
        );
        assertThat(Files.readString(workspace.resolve("AGENTS.md")))
            .isEqualTo("Review changes before finishing.");
    }
}
