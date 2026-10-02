package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;
import java.util.UUID;


public record ConversationResponse(
    UUID id,
    String title,
    Instant createdAt,
    Instant updatedAt) {

        public static ConversationResponse from(Conversation conversation) {
            return new ConversationResponse(
                conversation.id(),
                conversation.title(),
                conversation.createdAt(),
                conversation.updatedAt());
        }
}
