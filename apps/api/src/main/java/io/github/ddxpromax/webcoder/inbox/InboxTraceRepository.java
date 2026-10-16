package io.github.ddxpromax.webcoder.inbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL trace store: transactional replacement takes the role of Majo's file lock. */
@Repository
public class InboxTraceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InboxTraceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void create(String runId, String agentId, Map<String, Object> meta) {
        jdbc.update("""
            INSERT INTO inbox_traces (run_id, agent_id, status, meta, events, created_at, updated_at)
            VALUES (:runId, :agentId, 'running', CAST(:meta AS JSON), JSON_ARRAY(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE agent_id = VALUES(agent_id), status = 'running', meta = VALUES(meta),
              events = JSON_ARRAY(), error = NULL, completed_at = NULL, updated_at = UTC_TIMESTAMP(6)
            """, Map.of("runId", runId, "agentId", safe(agentId), "meta", json(meta)));
    }

    public void append(String runId, List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) return;
        Optional<Trace> current = find(runId);
        if (current.isEmpty()) return;
        List<Map<String, Object>> all = new ArrayList<>(current.get().events());
        for (Map<String, Object> event : events) {
            Map<String, Object> normalized = new LinkedHashMap<>(event);
            normalized.putIfAbsent("at", System.currentTimeMillis() / 1000L);
            all.add(normalized);
        }
        jdbc.update("UPDATE inbox_traces SET events = CAST(:events AS JSON), updated_at = UTC_TIMESTAMP(6) WHERE run_id = :runId",
            Map.of("runId", runId, "events", json(all)));
    }

    public void finalizeTrace(String runId, String status, String error) {
        jdbc.update("UPDATE inbox_traces SET status = :status, error = :error, completed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE run_id = :runId",
            new MapSqlParameterSource().addValue("runId", runId).addValue("status", safe(status)).addValue("error", error));
    }

    public Optional<Trace> find(String runId) {
        return jdbc.query("SELECT * FROM inbox_traces WHERE run_id = :runId", Map.of("runId", runId), (rs, row) -> new Trace(
            rs.getString("run_id"), rs.getString("agent_id"), rs.getString("status"), map(rs.getString("meta")),
            list(rs.getString("events")), rs.getString("error"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("completed_at", LocalDateTime.class))).stream().findFirst();
    }

    public boolean delete(String runId) {
        return jdbc.update("DELETE FROM inbox_traces WHERE run_id = :runId", Map.of("runId", runId)) > 0;
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String json(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception e) { throw new IllegalArgumentException("trace must be JSON serializable", e); } }
    private Map<String, Object> map(String value) { try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private List<Map<String, Object>> list(String value) {
        try {
            List<LinkedHashMap<String, Object>> parsed = mapper.readValue(value,
                new TypeReference<List<LinkedHashMap<String, Object>>>() {});
            return new ArrayList<>(parsed);
        } catch (Exception e) { return List.of(); }
    }

    public record Trace(String runId, String agentId, String status, Map<String, Object> meta, List<Map<String, Object>> events, String error, LocalDateTime createdAt, LocalDateTime completedAt) {}
}
