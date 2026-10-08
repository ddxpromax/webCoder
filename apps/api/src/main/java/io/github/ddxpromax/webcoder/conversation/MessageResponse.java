package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
    long id,
    UUID conversationId,
    String role,
    String content,
    String language,
    String status,
    Instant createdAt) {

        public static MessageResponse from(Message message) {
            return new MessageResponse(
                message.id(),
                message.conversationId(),
                message.role(),
                message.content(),
                message.language(),
                message.status(),
                message.createdAt());
        }
}
