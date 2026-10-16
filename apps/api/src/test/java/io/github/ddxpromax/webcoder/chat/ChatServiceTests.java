package io.github.ddxpromax.webcoder.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatServiceTests {
    @Test
    void firstTurnClaimsPrecreatedChatInsteadOfDuplicatingIt() {
        ChatRepository chats = mock(ChatRepository.class);
        ChatService service = new ChatService(
            chats, mock(MessageRepository.class)
        );
        ChatEntity placeholder = new ChatEntity();
        placeholder.setId("123e4567-e89b-12d3-a456-426614174000");
        String sessionId = "console:" + placeholder.getId();
        when(chats.findByAgentIdAndSessionId("research", sessionId))
            .thenReturn(Optional.empty());
        when(chats.findById(placeholder.getId()))
            .thenReturn(Optional.of(placeholder));
        when(chats.save(placeholder)).thenReturn(placeholder);

        ChatEntity resolved = service.getOrCreateBySession(
            "research", sessionId, "Implement a feature"
        );

        assertThat(resolved).isSameAs(placeholder);
        assertThat(resolved.getSessionId()).isEqualTo(sessionId);
        assertThat(resolved.getAgentId()).isEqualTo("research");
        assertThat(resolved.getTitle()).isEqualTo("Implement ");
        verify(chats).save(placeholder);
    }
}
