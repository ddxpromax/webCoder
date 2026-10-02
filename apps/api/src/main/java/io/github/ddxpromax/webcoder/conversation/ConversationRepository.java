package io.github.ddxpromax.webcoder.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


@Repository
public class ConversationRepository {

    private static final RowMapper<Conversation> ROW_MAPPER = (ResultSet resultSet, int rowNumber) -> mapConversation(resultSet);

    private final JdbcClient jdbcClient;

    public ConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Conversation create(String title) {
        return jdbcClient.sql("""
                insert into conversations (title)
                values (:title)
                returning id, title, created_at, updated_at
                """)
                .param("title", title)
                .query(ROW_MAPPER)
                .single();
    }

    public List<Conversation> findRecent(int limit) {
        return jdbcClient.sql("""
                select id, title, created_at, updated_at
                from conversations
                order by updated_at desc, id desc
                limit :limit
                """)
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }

    private static Conversation mapConversation(ResultSet resultSet) throws SQLException {
        return new Conversation(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("title"),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
