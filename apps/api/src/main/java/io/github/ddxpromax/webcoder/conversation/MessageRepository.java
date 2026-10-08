package io.github.ddxpromax.webcoder.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


@Repository
public class MessageRepository {

    public static final RowMapper<Message> ROW_MAPPER =
        (ResultSet resultSet, int rowNumber) -> mapMessage(resultSet);

    private final JdbcClient jdbcClient;

    public MessageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Message> findRecentPage(
        UUID conversationId,
        int fetchLimit,
        Instant beforeCreatedAt,
        Long beforeId) {
            if (beforeCreatedAt == null) {
                return jdbcClient.sql("""
                        select id, conversation_id, role, content, language, status, created_at
                        from messages
                        where conversation_id = :conversationId
                        order by created_at desc, id desc
                        limit :fetchLimit
                        """)
                        .param("conversationId", conversationId)
                        .param("fetchLimit", fetchLimit)
                        .query(ROW_MAPPER)
                        .list();
            }

            return jdbcClient.sql("""
                    select id, conversation_id, role, content, language, status, created_at
                    from messages
                    where conversation_id = :conversationId
                        and (created_at, id) < (:beforeCreatedAt, :beforeId)
                    order by created_at desc, id desc
                    limit :fetchLimit
                    """)
                    .param("conversationId", conversationId)
                    .param("beforeCreatedAt", beforeCreatedAt.atOffset(ZoneOffset.UTC))
                    .param("beforeId", beforeId)
                    .param("fetchLimit", fetchLimit)
                    .query(ROW_MAPPER)
                    .list();
    }

    private static Message mapMessage(ResultSet resultSet) throws SQLException {
        return new Message(
            resultSet.getLong("id"),
            resultSet.getObject("conversation_id", UUID.class),
            resultSet.getString("role"),
            resultSet.getString("content"),
            resultSet.getString("language"),
            resultSet.getString("status"),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public Message insert(
        UUID conversationId,
        String role,
        String content,
        String language,
        String status) {
            return jdbcClient.sql("""
                    insert into messages (conversation_id, role, content, language, status)
                    values (:conversationId, :role, :content, :language, :status)
                    returning id, conversation_id, role, content, language, status, created_at
                    """)
                    .param("conversationId", conversationId)
                    .param("role", role)
                    .param("content", content)
                    .param("language", language)
                    .param("status", status)
                    .query(ROW_MAPPER)
                    .single();
    }

    public int transitionPendingAssistantMessage(
        long messageId,
        String content,
        String status) {
            return jdbcClient.sql("""
                    update messages
                    set content = :content, status = :status
                    where id = :messageId
                        and role = 'assistant'
                        and status = 'pending'
                    """)
                    .param("messageId", messageId)
                    .param("content", content)
                    .param("status", status)
                    .update();
    }

    public int touchConversation(UUID conversationId) {
        return jdbcClient.sql("""
                update conversations
                set updated_at = now()
                where id = :conversationId
                """)
                .param("conversationId", conversationId)
                .update();
    }
}
