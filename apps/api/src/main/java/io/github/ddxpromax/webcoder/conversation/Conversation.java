package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;
import java.util.UUID;


public record Conversation(
    UUID id,
    String title,
    Instant createdAt,
    Instant updatedAt) {
}
