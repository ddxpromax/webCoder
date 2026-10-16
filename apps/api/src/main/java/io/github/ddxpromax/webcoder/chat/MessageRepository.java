package io.github.ddxpromax.webcoder.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MessageEntity> findByChatIdOrderByCreatedAtAsc(
        String chatId
    ) {
        return jdbc.query("""
            SELECT id, chat_id, role, content, tool_calls,
                   thinking, created_at
            FROM messages
            WHERE chat_id = :chatId
            ORDER BY created_at ASC, id ASC
            """, Map.of("chatId", chatId),
            MessageRepository::mapMessage);
    }

    public long countByChatId(String chatId) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE chat_id = :chatId",
            Map.of("chatId", chatId), Long.class
        );
        return count == null ? 0 : count;
    }

    public void deleteByChatId(String chatId) {
        jdbc.update("DELETE FROM messages WHERE chat_id = :chatId",
            Map.of("chatId", chatId));
    }

    public MessageEntity save(MessageEntity message) {
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO messages (
              chat_id, role, content, tool_calls, thinking, created_at
            ) VALUES (
              :chatId, :role, :content, :toolCalls, :thinking, :createdAt
            )
            """, new MapSqlParameterSource()
                .addValue("chatId", message.getChatId())
                .addValue("role", message.getRole())
                .addValue("content", message.getContent())
                .addValue("toolCalls", message.getToolCalls())
                .addValue("thinking", message.getThinking())
                .addValue("createdAt", message.getCreatedAt()),
            key, new String[] {"id"});
        if (key.getKey() != null) {
            message.setId(key.getKey().longValue());
        }
        return message;
    }

    private static MessageEntity mapMessage(
        ResultSet rs,
        int rowNumber
    ) throws SQLException {
        MessageEntity message = new MessageEntity();
        message.setId(rs.getLong("id"));
        message.setChatId(rs.getString("chat_id"));
        message.setRole(rs.getString("role"));
        message.setContent(rs.getString("content"));
        message.setToolCalls(rs.getString("tool_calls"));
        message.setThinking(rs.getString("thinking"));
        message.setCreatedAt(rs.getObject(
            "created_at", LocalDateTime.class
        ));
        return message;
    }
}
