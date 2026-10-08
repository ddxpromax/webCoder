package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;


public record MessageCursor(
    Instant createdAt,
    long id) {
}
