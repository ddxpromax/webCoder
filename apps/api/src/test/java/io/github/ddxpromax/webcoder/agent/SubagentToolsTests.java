package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentToolsTests {
    @TempDir
    Path workspace;

    @Test
    void toolsPassTheCurrentAgentAndWorkspaceToTheService() {
        SubagentService service = mock(SubagentService.class);
        SubagentTools tools = new SubagentTools(
            "builder", workspace, service
        );
        when(service.spawn("builder", workspace, "inspect tests"))
            .thenReturn("Tests inspected");
        when(service.chatWithAgent("reviewer", "Review this"))
            .thenReturn("Reviewed");
        when(service.submit("reviewer", "Review later"))
            .thenReturn("task_123");
        when(service.check("task_123"))
            .thenReturn(Map.of(
                "status", "completed",
                "result", "Done"
            ));

        assertThat(tools.spawnSubagent("inspect tests"))
            .isEqualTo("Tests inspected");
        assertThat(tools.chatWithAgent("reviewer", "Review this"))
            .isEqualTo("Reviewed");
        assertThat(tools.submitToAgent("reviewer", "Review later"))
            .contains("task_123");
        assertThat(tools.checkAgentTask("task_123"))
            .contains("completed", "Done");
        verify(service).spawn("builder", workspace, "inspect tests");
    }

    @Test
    void registryDoesNotOverwriteCancellationWithLateCompletion() {
        SubagentTaskRegistry registry = new SubagentTaskRegistry();
        SubagentTaskRegistry.Task task = registry.register("reviewer");
        assertThat(registry.cancel(task.taskId)).isTrue();
        registry.complete(task.taskId, "Late result");
        registry.fail(task.taskId, "Late failure");
        assertThat(registry.get(task.taskId).status)
            .isEqualTo("cancelled");
        assertThat(registry.get(task.taskId).result).isNull();
    }
}
