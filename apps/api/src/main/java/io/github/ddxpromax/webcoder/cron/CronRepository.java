package io.github.ddxpromax.webcoder.cron;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Handwritten MySQL statements for scheduled job specification, state and history. */
@Repository
public class CronRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CronRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public List<Job> list(String agentId) { return jdbc.query("SELECT * FROM cron_jobs WHERE agent_id = :agentId ORDER BY updated_at DESC, id DESC", Map.of("agentId", agentId), this::mapJob); }
    public Optional<Job> find(String agentId, String id) { return jdbc.query("SELECT * FROM cron_jobs WHERE agent_id = :agentId AND id = :id", Map.of("agentId", agentId, "id", id), this::mapJob).stream().findFirst(); }
    public List<Job> due(int limit) { return jdbc.query("SELECT * FROM cron_jobs WHERE paused = FALSE AND next_run_at IS NOT NULL AND next_run_at <= UTC_TIMESTAMP(6) ORDER BY next_run_at ASC LIMIT :limit", Map.of("limit", limit), this::mapJob); }

    public void upsert(String agentId, String id, Map<String, Object> spec, LocalDateTime nextRun) {
        jdbc.update("""
            INSERT INTO cron_jobs (id, agent_id, spec, paused, next_run_at, created_at, updated_at)
            VALUES (:id, :agentId, CAST(:spec AS JSON), FALSE, :nextRun, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE spec = VALUES(spec), paused = FALSE, next_run_at = VALUES(next_run_at), updated_at = UTC_TIMESTAMP(6)
            """, new MapSqlParameterSource().addValue("id", id).addValue("agentId", agentId).addValue("spec", json(spec)).addValue("nextRun", nextRun));
    }

    public boolean delete(String agentId, String id) { return jdbc.update("DELETE FROM cron_jobs WHERE agent_id = :agentId AND id = :id", Map.of("agentId", agentId, "id", id)) > 0; }
    public boolean pause(String agentId, String id) { return jdbc.update("UPDATE cron_jobs SET paused = TRUE, next_run_at = NULL, updated_at = UTC_TIMESTAMP(6) WHERE agent_id = :agentId AND id = :id", Map.of("agentId", agentId, "id", id)) > 0; }
    public boolean resume(String agentId, String id, LocalDateTime nextRun) { return jdbc.update("UPDATE cron_jobs SET paused = FALSE, next_run_at = :nextRun, updated_at = UTC_TIMESTAMP(6) WHERE agent_id = :agentId AND id = :id", new MapSqlParameterSource().addValue("agentId", agentId).addValue("id", id).addValue("nextRun", nextRun)) > 0; }

    /** Atomic cross-instance claim: a job cannot exceed its own concurrency limit. */
    public boolean claim(String id, int maxConcurrency, LocalDateTime nextRun) {
        return jdbc.update("""
            UPDATE cron_jobs SET running_count = running_count + 1, last_started_at = UTC_TIMESTAMP(6),
              last_status = 'running', last_error = NULL, next_run_at = :nextRun, updated_at = UTC_TIMESTAMP(6)
            WHERE id = :id AND paused = FALSE AND running_count < :maxConcurrency
            """, new MapSqlParameterSource().addValue("id", id).addValue("maxConcurrency", maxConcurrency).addValue("nextRun", nextRun)) > 0;
    }

    public void finish(String id, String status, String error) { jdbc.update("UPDATE cron_jobs SET running_count = GREATEST(running_count - 1, 0), last_finished_at = UTC_TIMESTAMP(6), last_status = :status, last_error = :error, updated_at = UTC_TIMESTAMP(6) WHERE id = :id", new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("error", error)); }
    public void disable(String id) { jdbc.update("UPDATE cron_jobs SET paused = TRUE, next_run_at = NULL, updated_at = UTC_TIMESTAMP(6) WHERE id = :id", Map.of("id", id)); }
    public void setNextRun(String id, LocalDateTime nextRun) { jdbc.update("UPDATE cron_jobs SET next_run_at = :nextRun, updated_at = UTC_TIMESTAMP(6) WHERE id = :id", new MapSqlParameterSource().addValue("id", id).addValue("nextRun", nextRun)); }

    public void addRun(Run run) { jdbc.update("INSERT INTO cron_job_runs (id, job_id, agent_id, status, started_at, finished_at, error, result) VALUES (:id, :jobId, :agentId, :status, :startedAt, :finishedAt, :error, CAST(:result AS JSON))", new MapSqlParameterSource().addValue("id", run.id()).addValue("jobId", run.jobId()).addValue("agentId", run.agentId()).addValue("status", run.status()).addValue("startedAt", run.startedAt()).addValue("finishedAt", run.finishedAt()).addValue("error", run.error()).addValue("result", json(run.result()))); }
    public List<Map<String, Object>> history(String agentId, String jobId) { return jdbc.query("SELECT * FROM cron_job_runs WHERE agent_id = :agentId AND job_id = :jobId ORDER BY started_at DESC, id DESC LIMIT 50", Map.of("agentId", agentId, "jobId", jobId), (rs, row) -> { Map<String, Object> item = new LinkedHashMap<>(); item.put("run_at", rs.getObject("started_at", LocalDateTime.class).toString()); item.put("status", rs.getString("status")); item.put("error", rs.getString("error")); item.put("trigger", map(rs.getString("result")).getOrDefault("trigger", "scheduled")); return item; }); }

    private Job mapJob(ResultSet rs, int row) throws SQLException { return new Job(rs.getString("id"), rs.getString("agent_id"), map(rs.getString("spec")), rs.getBoolean("paused"), rs.getInt("running_count"), rs.getObject("last_started_at", LocalDateTime.class), rs.getObject("last_finished_at", LocalDateTime.class), rs.getString("last_status"), rs.getString("last_error"), rs.getObject("next_run_at", LocalDateTime.class)); }
    private String json(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception e) { throw new IllegalArgumentException("cron payload must be JSON serializable", e); } }
    private Map<String, Object> map(String value) { try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    public record Job(String id, String agentId, Map<String, Object> spec, boolean paused, int runningCount, LocalDateTime lastStartedAt, LocalDateTime lastFinishedAt, String lastStatus, String lastError, LocalDateTime nextRunAt) {}
    public record Run(String id, String jobId, String agentId, String status, LocalDateTime startedAt, LocalDateTime finishedAt, String error, Map<String, Object> result) {}
}
