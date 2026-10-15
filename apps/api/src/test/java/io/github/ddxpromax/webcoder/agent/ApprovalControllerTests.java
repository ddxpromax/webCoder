package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void guardedWriteWaitsForMatchingRootSessionApproval()
        throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalHook hook = hook(store);
        var mvc = MockMvcBuilders.standaloneSetup(
            new ApprovalController(store)
        ).build();
        PreActingEvent event = event("write_file");
        CompletableFuture<PreActingEvent> result =
            CompletableFuture.supplyAsync(() -> hook.onEvent(event)
                .map(item -> (PreActingEvent) item).block()
            );
        ApprovalStore.ApprovalRequest request = awaitRequest(store);
        mvc.perform(get("/api/approval/list")
                .param("session_id", "session-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.pending_approvals[0].tool_name")
                .value("write_file"));
        mvc.perform(post("/api/approval/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"request_id":"%s","session_id":"other"}
                    """.formatted(request.requestId)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/approval/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"request_id":"%s","session_id":"session-1"}
                    """.formatted(request.requestId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        assertThat(result.get(2, TimeUnit.SECONDS).getToolUse()
            .getState()).isNotEqualTo(ToolCallState.FINISHED);
        assertThat(store.listPending()).isEmpty();
    }

    @Test
    void denialReplacesToolCallWithFinishedRejection()
        throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalHook hook = hook(store);
        var mvc = MockMvcBuilders.standaloneSetup(
            new ApprovalController(store)
        ).build();
        PreActingEvent event = event("edit_file");
        CompletableFuture<PreActingEvent> result =
            CompletableFuture.supplyAsync(() -> hook.onEvent(event)
                .map(item -> (PreActingEvent) item).block()
            );
        ApprovalStore.ApprovalRequest request = awaitRequest(store);
        mvc.perform(post("/api/approval/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"request_id":"%s","session_id":"session-1",
                     "reason":"No changes"}
                    """.formatted(request.requestId)))
            .andExpect(status().isOk());
        ToolUseBlock toolUse = result.get(2, TimeUnit.SECONDS)
            .getToolUse();
        assertThat(toolUse.getState())
            .isEqualTo(ToolCallState.FINISHED);
        assertThat(toolUse.getContent())
            .contains("User denied");
    }

    @Test
    void dangerousCommandIsBlockedBeforeApproval() {
        ApprovalStore store = new ApprovalStore();
        ApprovalHook hook = hook(store);
        PreActingEvent event = event(
            "execute_command",
            Map.of("command", "rm -rf /")
        );
        hook.onEvent(event).block();
        assertThat(event.getToolUse().getState())
            .isEqualTo(ToolCallState.FINISHED);
        assertThat(event.getToolUse().getContent())
            .contains("Tool Guard");
        assertThat(store.listPending()).isEmpty();
    }

    private ApprovalHook hook(ApprovalStore store) {
        AgentStore agents = new AgentStore(
            new MajoWorkingDirectory(workingDir.toString()),
            new JsonFileStore(new ObjectMapper())
        );
        ApprovalHook hook = new ApprovalHook(
            "default", agents, store,
            new ToolGuardService(agents),
            new FileGuardService(agents),
            workingDir
        );
        hook.setRuntimeContext(RuntimeContext.builder()
            .sessionId("session-1").build());
        return hook;
    }

    private static PreActingEvent event(String toolName) {
        return event(toolName, Map.of("path", "file.txt"));
    }

    private static PreActingEvent event(
        String toolName,
        Map<String, Object> input
    ) {
        return new PreActingEvent(
            mock(Agent.class),
            new Toolkit(),
            new ToolUseBlock(
                "tool-1", toolName,
                input
            )
        );
    }

    private static ApprovalStore.ApprovalRequest awaitRequest(
        ApprovalStore store
    ) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var pending = store.listPending();
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Approval request was not registered");
    }
}
