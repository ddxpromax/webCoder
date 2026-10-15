package io.github.ddxpromax.webcoder.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit MySQL SQL for Majo's aggregate and per-turn token records. */
@Repository
public class TokenUsageRepository {
    public record DailyRecord(
        String date,
        String providerId,
        String model,
        long promptTokens,
        long completionTokens,
        long callCount
    ) {}

    private final NamedParameterJdbcTemplate jdbc;

    public TokenUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
        String agentId,
        String chatId,
        String providerId,
        String model,
        int inputTokens,
        int outputTokens,
        long durationMs
    ) {
        String today = LocalDate.now().toString();
        MapSqlParameterSource args = new MapSqlParameterSource()
            .addValue("day", today)
            .addValue("agent", agentId)
            .addValue("chat", chatId)
            .addValue("provider", providerId)
            .addValue("model", model)
            .addValue("input", Math.max(0, inputTokens))
            .addValue("output", Math.max(0, outputTokens))
            .addValue("duration", Math.max(0, durationMs));
        jdbc.update("""
            INSERT INTO token_usage
                (usage_date, provider_id, model,
                 prompt_tokens, completion_tokens, call_count)
            VALUES
                (:day, :provider, :model, :input, :output, 1)
            """, args);
        jdbc.update("""
            INSERT INTO token_usage_turns
                (agent_id, chat_id, provider_id, model,
                 input_tokens, output_tokens, duration_ms)
            VALUES
                (:agent, :chat, :provider, :model,
                 :input, :output, :duration)
            """, args);
    }

    public List<DailyRecord> findByDateRange(
        String start,
        String end,
        String model,
        String provider
    ) {
        String sql = """
            SELECT usage_date, provider_id, model,
                   SUM(prompt_tokens) AS prompt_tokens,
                   SUM(completion_tokens) AS completion_tokens,
                   SUM(call_count) AS call_count
              FROM token_usage
             WHERE usage_date >= :start
               AND usage_date <= :end
            """;
        MapSqlParameterSource args = new MapSqlParameterSource()
            .addValue("start", start)
            .addValue("end", end);
        if (model != null && !model.isBlank()) {
            sql += " AND model = :model";
            args.addValue("model", model);
        }
        if (provider != null && !provider.isBlank()) {
            sql += " AND provider_id = :provider";
            args.addValue("provider", provider);
        }
        sql += " GROUP BY usage_date, provider_id, model"
            + " ORDER BY usage_date, provider_id, model";
        return jdbc.query(sql, args, (rs, rowNum) -> readDaily(rs));
    }

    public List<Map<String, Object>> agentStatsSince(
        LocalDateTime since
    ) {
        return jdbc.query("""
            SELECT agent_id,
                   SUM(input_tokens) AS input_tokens,
                   SUM(output_tokens) AS output_tokens,
                   COUNT(*) AS turns,
                   SUM(duration_ms) AS duration_ms
              FROM token_usage_turns
             WHERE created_at >= :since
             GROUP BY agent_id
             ORDER BY SUM(input_tokens) + SUM(output_tokens) DESC
            """, Map.of("since", since), (rs, rowNum) -> Map.of(
                "agent_id", rs.getString("agent_id"),
                "input_tokens", rs.getLong("input_tokens"),
                "output_tokens", rs.getLong("output_tokens"),
                "turns", rs.getLong("turns"),
                "duration_ms", rs.getLong("duration_ms")
            ));
    }

    private static DailyRecord readDaily(ResultSet rs)
        throws SQLException {
        return new DailyRecord(
            rs.getString("usage_date"),
            rs.getString("provider_id"),
            rs.getString("model"),
            rs.getLong("prompt_tokens"),
            rs.getLong("completion_tokens"),
            rs.getLong("call_count")
        );
    }
}
