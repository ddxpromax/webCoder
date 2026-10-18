package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ConsoleControllerTests {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsMajoEventsAndPersistsCompletedMessages()
        throws Exception {
        Fixture fixture = fixture(Flux.just(
            new TextBlockStartEvent("reply", "block"),
            new TextBlockDeltaEvent("reply", "block", "hello"),
            new TextBlockEndEvent("reply", "block")
        ));
        List<String> serialized = fixture.controller.consoleChat(
            Map.of(
                "input", "Write code",
                "session_id", "session-1"
            ),
            new MockHttpServletRequest()
        ).collectList().block();
        assertThat(serialized).hasSize(8);
        List<JsonNode> events = serialized.stream().map(value -> {
            try {
                return mapper.readTree(value);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).toList();
        assertThat(events.get(0).path("status").asText())
            .isEqualTo("created");
        assertThat(events.get(1).path("status").asText())
            .isEqualTo("in_progress");
        assertThat(events.get(3).path("text").asText())
            .isEqualTo("hello");
        assertThat(events.get(3).path("delta").asBoolean())
            .isTrue();
        assertThat(events.get(6).path("output").get(0)
            .path("content").get(0).path("text").asText())
            .isEqualTo("hello");
        assertThat(events.get(7).path("type").asText())
            .isEqualTo("turn_usage");
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).path("sequence_number")
                .asInt()).isEqualTo(i + 1);
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages =
            ArgumentCaptor.forClass(List.class);
        verify(fixture.chats).saveMessages(
            org.mockito.ArgumentMatchers.eq("chat-1"),
            messages.capture()
        );
        assertThat(messages.getValue()).hasSize(2);
        assertThat(messages.getValue().get(0).get("role"))
            .isEqualTo("user");
        assertThat(messages.getValue().get(1).get("content"))
            .contains("hello");
        assertThat(fixture.turns.isActive("chat-1")).isFalse();
        assertThat(fixture.tasks.getStatus("chat-1"))
            .isEqualTo("idle");
    }

    @Test
    void stopWaitsUntilChatMessagesArePersisted() {
        Sinks.Many<AgentEvent> sink = Sinks.many().multicast()
            .onBackpressureBuffer();
        Fixture fixture = fixture(sink.asFlux());
        doAnswer(invocation -> {
            sink.tryEmitComplete();
            return null;
        }).when(fixture.agent).interrupt();

        fixture.controller.consoleChat(
            Map.of("input", "Stop this", "session_id", "session-2"),
            new MockHttpServletRequest()
        ).subscribe();
        assertThat(fixture.turns.isActive("chat-1")).isTrue();
        assertThat(fixture.controller.stopChat("chat-1"))
            .containsEntry("stopped", true);
        verify(fixture.chats).saveMessages(anyString(), any());
        assertThat(fixture.turns.isActive("chat-1")).isFalse();
    }

    @Test
    void memoryCommandUsesLocalReplyAndPersistsTheTurn()
        throws Exception {
        Fixture fixture = fixture(Flux.empty());
        when(fixture.memoryCommands.execute(
            "default", " search mysql"
        )).thenReturn("One matching memory: MySQL");

        List<String> serialized = fixture.controller.consoleChat(
            Map.of(
                "input", "/memory search mysql",
                "session_id", "memory-session"
            ),
            new MockHttpServletRequest()
        ).collectList().block();

        assertThat(serialized).hasSize(8);
        assertThat(mapper.readTree(serialized.get(6))
            .path("output").get(0).path("content")
            .get(0).path("text").asText())
            .isEqualTo("One matching memory: MySQL");
        verify(fixture.memoryCommands).execute(
            "default", " search mysql"
        );
        verify(fixture.chats).saveMessages(anyString(), any());
    }

    @Test
    void completedModelCallRecordsUsageForTheChat() throws Exception {
        Fixture fixture = fixture(Flux.just(
            new ModelCallEndEvent(
                "reply", new ChatUsage(14, 4, 0.5)
            )
        ));
        List<String> events = fixture.controller.consoleChat(
            Map.of("input", "Count this", "session_id", "usage-session"),
            new MockHttpServletRequest()
        ).collectList().block();

        assertThat(mapper.readTree(events.getLast())
            .path("usage").path("prompt_tokens").asInt())
            .isEqualTo(14);
        verify(fixture.tokenUsage).record(
            eq("default"), eq("chat-1"),
            eq("provider"), eq("model"),
            eq(14), eq(4), anyLong()
        );
    }

    @Test
    void turnsUploadedImagesIntoModelContentAndPersistsTheirReference()
        throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("uploads"));
        Files.write(temporaryDirectory.resolve("uploads/screenshot.png"),
            new byte[] {1, 2, 3});
        Fixture fixture = fixture(Flux.empty());

        fixture.controller.consoleChat(Map.of(
            "input", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "Read this image"),
                Map.of("type", "image", "image_url", "screenshot.png",
                    "name", "screenshot.png")
            ))),
            "session_id", "attachment-session"
        ), new MockHttpServletRequest()).collectList().block();

        ArgumentCaptor<UserMessage> message = ArgumentCaptor.forClass(
            UserMessage.class
        );
        verify(fixture.agent).streamEvents(
            message.capture(), any(RuntimeContext.class)
        );
        assertThat(message.getValue().getContentBlocks(ImageBlock.class))
            .hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> persisted =
            ArgumentCaptor.forClass(List.class);
        verify(fixture.chats).saveMessages(eq("chat-1"), persisted.capture());
        assertThat(persisted.getValue().getFirst().get("content"))
            .contains("image_url", "screenshot.png");
    }

    @Test
    void loopStatusIsIdleWithoutASelectedLoopSession() {
        Fixture fixture = fixture(Flux.empty());
        assertThat(fixture.controller.loopStatus(null, null))
            .containsEntry("state", "idle")
            .containsEntry("mode", null);
    }

    private Fixture fixture(Flux<AgentEvent> stream) {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        HarnessAgent.Builder builder =
            mock(HarnessAgent.Builder.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        ChatService chats = mock(ChatService.class);
        TaskTracker tasks = new TaskTracker();
        ActiveTurnRegistry turns = new ActiveTurnRegistry();
        ApprovalStore approvals = new ApprovalStore();
        MemoryCommandService memoryCommands = mock(
            MemoryCommandService.class
        );
        TokenUsageService tokenUsage = mock(TokenUsageService.class);
        ModelRoutingService modelRouting = mock(
            ModelRoutingService.class
        );
        AgentStore agents = mock(AgentStore.class);
        when(modelRouting.resolveEffectiveModel(anyString()))
            .thenReturn(new ModelRoutingService.ModelSlot(
                "provider", "model"
            ));
        ChatEntity chat = new ChatEntity();
        chat.setId("chat-1");
        chat.setTitle("Stop this");
        when(chats.getOrCreateBySession(
            anyString(), anyString(), anyString()
        )).thenReturn(chat);
        when(factory.workspaceFor(anyString()))
            .thenReturn(Path.of("/tmp"));
        when(factory.builder(
            anyString(), anyString(), any(Path.class), any()
        )).thenReturn(builder);
        when(builder.build()).thenReturn(agent);
        when(agent.streamEvents(
            any(UserMessage.class), any(RuntimeContext.class)
        )).thenReturn(stream);
        return new Fixture(
            new ConsoleController(
                factory, chats, tasks, turns, approvals,
                memoryCommands, tokenUsage, modelRouting,
                agents, new io.github.ddxpromax.webcoder.loop.LoopSessionManager(),
                mapper, new MajoWorkingDirectory(temporaryDirectory.toString()), "0.4.0"
            ),
            chats, tasks, turns, agent, memoryCommands,
            tokenUsage
        );
    }

    private record Fixture(
        ConsoleController controller,
        ChatService chats,
        TaskTracker tasks,
        ActiveTurnRegistry turns,
        HarnessAgent agent,
        MemoryCommandService memoryCommands,
        TokenUsageService tokenUsage
    ) {
    }
}
