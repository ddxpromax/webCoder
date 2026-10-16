package io.github.ddxpromax.webcoder.inbox;

import java.time.LocalDateTime;
import java.util.Map;

public record InboxEvent(
    String id,
    String agentId,
    String sourceType,
    String sourceId,
    String eventType,
    String status,
    String severity,
    String title,
    String body,
    Map<String, Object> payload,
    boolean read,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
