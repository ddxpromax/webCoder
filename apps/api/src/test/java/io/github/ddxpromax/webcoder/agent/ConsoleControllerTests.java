package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ConsoleControllerTests {
    private final ObjectMapper mapper = new ObjectMapper();

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

    private Fixture fixture(Flux<AgentEvent> stream) {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        HarnessAgent.Builder builder =
            mock(HarnessAgent.Builder.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        ChatService chats = mock(ChatService.class);
        TaskTracker tasks = new TaskTracker();
        ActiveTurnRegistry turns = new ActiveTurnRegistry();
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
                factory, chats, tasks, turns, mapper, "0.4.0"
            ),
            chats, tasks, turns, agent
        );
    }

    private record Fixture(
        ConsoleController controller,
        ChatService chats,
        TaskTracker tasks,
        ActiveTurnRegistry turns,
        HarnessAgent agent
    ) {
    }
}
