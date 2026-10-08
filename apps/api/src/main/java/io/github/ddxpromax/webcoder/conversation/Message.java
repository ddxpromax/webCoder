package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;
import java.util.UUID;


public record Message(
    long id,
    UUID conversationId,
    String role,
    String content,
    String language,
    String status,
    Instant createdAt) {
}
