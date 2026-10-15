package io.github.ddxpromax.webcoder.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL chat queries are explicit to keep ordering and indexes reviewable. */
@Repository
public class ChatRepository {
    private static final String COLUMNS = """
        id, title, status, session_id, user_id, channel,
        pinned, archived_at, agent_id, project_dir,
        created_at, updated_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ChatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChatEntity> findAllByOrderByUpdatedAtDesc() {
        return list("", Map.of());
    }

    public List<ChatEntity>
        findAllByArchivedAtIsNullOrderByUpdatedAtDesc() {
        return list("WHERE archived_at IS NULL", Map.of());
    }

    public List<ChatEntity>
        findAllByArchivedAtIsNotNullOrderByUpdatedAtDesc() {
        return list("WHERE archived_at IS NOT NULL", Map.of());
    }

    public List<ChatEntity> findAllByAgentIdOrderByUpdatedAtDesc(
        String agentId
    ) {
        return list("WHERE agent_id = :agentId",
            Map.of("agentId", agentId));
    }

    public List<ChatEntity>
        findAllByAgentIdAndArchivedAtIsNullOrderByUpdatedAtDesc(
            String agentId
        ) {
        return list("WHERE agent_id = :agentId "
                + "AND archived_at IS NULL",
            Map.of("agentId", agentId));
    }

    public List<ChatEntity>
        findAllByAgentIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(
            String agentId
        ) {
        return list("WHERE agent_id = :agentId "
                + "AND archived_at IS NOT NULL",
            Map.of("agentId", agentId));
    }

    public Optional<ChatEntity> findById(String id) {
        return jdbc.query(
            "SELECT " + COLUMNS
                + " FROM chats WHERE id = :id LIMIT 1",
            Map.of("id", id), ChatRepository::mapChat
        ).stream().findFirst();
    }

    public Optional<ChatEntity> findBySessionId(String sessionId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM chats "
                + "WHERE session_id = :sessionId "
                + "ORDER BY updated_at DESC, id DESC LIMIT 1",
            Map.of("sessionId", sessionId), ChatRepository::mapChat
        ).stream().findFirst();
    }

    public Optional<ChatEntity> findByAgentIdAndSessionId(
        String agentId,
        String sessionId
    ) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM chats "
                + "WHERE agent_id = :agentId "
                + "AND session_id = :sessionId "
                + "ORDER BY updated_at DESC, id DESC LIMIT 1",
            Map.of("agentId", agentId, "sessionId", sessionId),
            ChatRepository::mapChat
        ).stream().findFirst();
    }

    public ChatEntity save(ChatEntity chat) {
        if (chat.getCreatedAt() == null) {
            chat.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        if (chat.getUpdatedAt() == null) {
            chat.setUpdatedAt(chat.getCreatedAt());
        }
        jdbc.update("""
            INSERT INTO chats (
              id, title, status, session_id, user_id, channel,
              pinned, archived_at, agent_id, project_dir,
              created_at, updated_at
            ) VALUES (
              :id, :title, :status, :sessionId, :userId, :channel,
              :pinned, :archivedAt, :agentId, :projectDir,
              :createdAt, :updatedAt
            ) ON DUPLICATE KEY UPDATE
              title = VALUES(title), status = VALUES(status),
              session_id = VALUES(session_id),
              user_id = VALUES(user_id), channel = VALUES(channel),
              pinned = VALUES(pinned), archived_at = VALUES(archived_at),
              agent_id = VALUES(agent_id), project_dir = VALUES(project_dir),
              updated_at = VALUES(updated_at)
            """, new MapSqlParameterSource()
                .addValue("id", chat.getId())
                .addValue("title", chat.getTitle())
                .addValue("status", chat.getStatus())
                .addValue("sessionId", chat.getSessionId())
                .addValue("userId", chat.getUserId())
                .addValue("channel", chat.getChannel())
                .addValue("pinned", chat.getPinned())
                .addValue("archivedAt", chat.getArchivedAt())
                .addValue("agentId", chat.getAgentId())
                .addValue("projectDir", chat.getProjectDir())
                .addValue("createdAt", chat.getCreatedAt())
                .addValue("updatedAt", chat.getUpdatedAt()));
        return chat;
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM chats WHERE id = :id",
            Map.of("id", id));
    }

    private List<ChatEntity> list(
        String where,
        Map<String, ?> params
    ) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM chats " + where
                + " ORDER BY updated_at DESC, id DESC",
            params,
            ChatRepository::mapChat
        );
    }

    private static ChatEntity mapChat(
        ResultSet rs,
        int rowNumber
    ) throws SQLException {
        ChatEntity chat = new ChatEntity();
        chat.setId(rs.getString("id"));
        chat.setTitle(rs.getString("title"));
        chat.setStatus(rs.getString("status"));
        chat.setSessionId(rs.getString("session_id"));
        chat.setUserId(rs.getString("user_id"));
        chat.setChannel(rs.getString("channel"));
        chat.setPinned(rs.getBoolean("pinned"));
        chat.setArchivedAt(rs.getObject(
            "archived_at", LocalDateTime.class
        ));
        chat.setAgentId(rs.getString("agent_id"));
        chat.setProjectDir(rs.getString("project_dir"));
        chat.setCreatedAt(rs.getObject(
            "created_at", LocalDateTime.class
        ));
        chat.setUpdatedAt(rs.getObject(
            "updated_at", LocalDateTime.class
        ));
        return chat;
    }
}
