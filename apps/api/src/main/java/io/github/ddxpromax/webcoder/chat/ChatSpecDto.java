package io.github.ddxpromax.webcoder.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public record ChatSpecDto(
    String id,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("user_id") String userId,
    String channel,
    String name,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    Map<String, Object> meta,
    String status,
    Boolean pinned,
    @JsonProperty("archived_at") String archivedAt,
    Boolean archived
) {
    public static ChatSpecDto from(ChatEntity chat) {
        String sessionId = chat.getSessionId().isEmpty()
            ? "console:" + chat.getId()
            : chat.getSessionId();

        return new ChatSpecDto(
            chat.getId(),
            sessionId,
            chat.getUserId(),
            chat.getChannel(),
            chat.getTitle(),
            chat.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            chat.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            Map.of(),
            chat.getStatus(),
            chat.getPinned(),
            chat.getArchivedAt() == null ? null
                : chat.getArchivedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            chat.getArchivedAt() != null
        );
    }
}