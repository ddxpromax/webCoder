package io.github.ddxpromax.webcoder.conversation;

import java.util.List;
import java.util.UUID;

public record MessagePageResponse(
    UUID conversationId,
    List<MessageResponse> messages,
    MessageCursor nextCursor) {
}
