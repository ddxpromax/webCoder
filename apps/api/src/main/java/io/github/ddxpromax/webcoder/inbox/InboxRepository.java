package io.github.ddxpromax.webcoder.inbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit MySQL storage for Majo-compatible inbox events. */
@Repository
public class InboxRepository {
    private static final int MAX_EVENTS = 5000;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public InboxEvent insert(InboxEvent event) {
        jdbc.update("""
            INSERT INTO inbox_events (
              id, agent_id, source_type, source_id, event_type, status,
              severity, title, body, payload, is_read, created_at, updated_at
            ) VALUES (
              :id, :agentId, :sourceType, :sourceId, :eventType, :status,
              :severity, :title, :body, CAST(:payload AS JSON), :read, :createdAt, :updatedAt
            )
            """, params(event));
        jdbc.update("""
            DELETE FROM inbox_events
            WHERE id IN (
              SELECT id FROM (
                SELECT id FROM inbox_events
                ORDER BY created_at DESC, id DESC
                LIMIT 18446744073709551615 OFFSET :maxEvents
              ) overflow_events
            )
            """, Map.of("maxEvents", MAX_EVENTS));
        return event;
    }

    public List<InboxEvent> list(InboxQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM inbox_events WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("limit", query.limit()).addValue("offset", query.offset());
        if (!query.sourceType().isBlank()) {
            sql.append(" AND source_type = :sourceType");
            params.addValue("sourceType", query.sourceType());
        }
        if (!query.status().isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", query.status());
        }
        if (!query.agentId().isBlank()) {
            sql.append(" AND agent_id = :agentId");
            params.addValue("agentId", query.agentId());
        }
        if (query.unreadOnly()) sql.append(" AND is_read = FALSE");
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, this::mapEvent);
    }

    public Optional<InboxEvent> find(String id) {
        return jdbc.query("SELECT * FROM inbox_events WHERE id = :id", Map.of("id", id), this::mapEvent)
            .stream().findFirst();
    }

    public int markRead(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update("UPDATE inbox_events SET is_read = TRUE, updated_at = UTC_TIMESTAMP(6) WHERE id IN (:ids) AND is_read = FALSE", Map.of("ids", ids));
    }

    public int markAllRead() {
        return jdbc.update("UPDATE inbox_events SET is_read = TRUE, updated_at = UTC_TIMESTAMP(6) WHERE is_read = FALSE", Map.of());
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM inbox_events WHERE id = :id", Map.of("id", id)) > 0;
    }

    public boolean referencesRun(String runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_events WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.run_id')) = :runId", Map.of("runId", runId), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource params(InboxEvent event) {
        return new MapSqlParameterSource()
            .addValue("id", event.id()).addValue("agentId", event.agentId())
            .addValue("sourceType", event.sourceType()).addValue("sourceId", event.sourceId())
            .addValue("eventType", event.eventType()).addValue("status", event.status())
            .addValue("severity", event.severity()).addValue("title", event.title())
            .addValue("body", event.body()).addValue("payload", json(event.payload()))
            .addValue("read", event.read()).addValue("createdAt", event.createdAt())
            .addValue("updatedAt", event.updatedAt());
    }

    private InboxEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return new InboxEvent(rs.getString("id"), rs.getString("agent_id"), rs.getString("source_type"),
            rs.getString("source_id"), rs.getString("event_type"), rs.getString("status"),
            rs.getString("severity"), rs.getString("title"), rs.getString("body"),
            map(rs.getString("payload")), rs.getBoolean("is_read"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("payload must be JSON serializable", exception); }
    }

    private Map<String, Object> map(String value) {
        try { return mapper.readValue(value == null ? "{}" : value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    public record InboxQuery(int limit, int offset, String sourceType, String status, String agentId, boolean unreadOnly) {
        public InboxQuery {
            limit = Math.min(Math.max(limit, 1), 500);
            offset = Math.max(offset, 0);
            sourceType = sourceType == null ? "" : sourceType;
            status = status == null ? "" : status;
            agentId = agentId == null ? "" : agentId;
        }
    }
}
