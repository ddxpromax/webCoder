package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.chat.MessageEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentStatsControllerTests {
    @Test
    void countsDailyActiveSessionsFromUserMessages() {
        ChatEntity chat = new ChatEntity(); chat.setId("chat-1"); chat.setChannel("console"); chat.setCreatedAt(LocalDateTime.of(2026, 10, 17, 9, 0));
        MessageEntity first = new MessageEntity(); first.setRole("user"); first.setCreatedAt(LocalDateTime.of(2026, 10, 17, 9, 1));
        MessageEntity second = new MessageEntity(); second.setRole("user"); second.setCreatedAt(LocalDateTime.of(2026, 10, 17, 9, 2));
        ChatService chats = Mockito.mock(ChatService.class); TokenUsageService tokens = Mockito.mock(TokenUsageService.class);
        Mockito.when(chats.list()).thenReturn(List.of(chat)); Mockito.when(chats.getMessages("chat-1")).thenReturn(List.of(first, second));
        Mockito.when(tokens.summary("2026-10-17", "2026-10-17", null, null)).thenReturn(Map.of("total_prompt_tokens", 0L, "total_completion_tokens", 0L, "total_calls", 0L));

        Map<String, Object> result = new AgentStatsController(chats, tokens).stats("2026-10-17", "2026-10-17");
        Map<?, ?> day = (Map<?, ?>) ((List<?>) result.get("by_date")).getFirst();
        assertThat(day.get("active_sessions")).isEqualTo(1);
        assertThat(result.get("total_active_sessions")).isEqualTo(1);
    }
}
