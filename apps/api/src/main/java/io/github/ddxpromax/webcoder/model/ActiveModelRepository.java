package io.github.ddxpromax.webcoder.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActiveModelRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ActiveModelRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActiveRow> findGlobal() {
        List<ActiveRow> rows = jdbc.query(
            """
            SELECT provider_id, model_id
            FROM active_models
            WHERE scope = 'global'
              AND agent_id IS NULL
            LIMIT 2
            """,
            Map.of(),
            (rs, rowNum) -> new ActiveRow(
                rs.getString("provider_id"),
                rs.getString("model_id")
            )
        );
        return single(rows);
    }

    public Optional<ActiveRow> findByScopeAndAgentId(
        String scope,
        String agentId
    ) {
        List<ActiveRow> rows = jdbc.query(
            """
            SELECT provider_id, model_id
            FROM active_models
            WHERE scope = :scope
              AND agent_id = :agentId
            LIMIT 2
            """,
            Map.of("scope", scope, "agentId", agentId),
            (rs, rowNum) -> new ActiveRow(
                rs.getString("provider_id"),
                rs.getString("model_id")
            )
        );
        return single(rows);
    }

    public void setGlobal(String providerId, String modelId) {
        // V2 creates the global sentinel. Update it instead of inserting
        // another NULL agent_id row.
        if (findGlobal().isEmpty()) {
            throw new IllegalStateException(
                "Global active model row is missing"
            );
        }

        jdbc.update(
            """
            UPDATE active_models
            SET provider_id = :providerId,
                model_id = :modelId
            WHERE scope = 'global'
              AND agent_id IS NULL
            """,
            Map.of(
                "providerId", providerId,
                "modelId", modelId
            )
        );
    }

    public void setAgent(
        String agentId,
        String providerId,
        String modelId
    ) {
        jdbc.update(
            """
            INSERT INTO active_models (
                scope, agent_id, provider_id, model_id
            ) VALUES (
                'agent', :agentId, :providerId, :modelId
            )
            ON DUPLICATE KEY UPDATE
                provider_id = :providerId,
                model_id = :modelId
            """,
            Map.of(
                "agentId", agentId,
                "providerId", providerId,
                "modelId", modelId
            )
        );
    }

    private static Optional<ActiveRow> single(List<ActiveRow> rows) {
        if (rows.size() > 1) {
            throw new IllegalStateException(
                "Multiple active model rows exist for one scope"
            );
        }
        return rows.stream().findFirst();
    }

    public record ActiveRow(String providerId, String modelId) {}
}