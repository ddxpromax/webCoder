package io.github.ddxpromax.webcoder.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Repository
public class ProviderModelRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProviderModelRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ModelMetadata> findByProviderIdAndModelId(
        String providerId,
        String modelId
    ) {
        List<ModelMetadata> rows = jdbc.query(
            """
            SELECT max_input_length
            FROM provider_models
            WHERE provider_id = :providerId
              AND model_id = :modelId
            LIMIT 2
            """,
            Map.of(
                "providerId", providerId,
                "modelId", modelId
            ),
            (rs, rowNum) -> new ModelMetadata(
                rs.getInt("max_input_length")
            )
        );

        if (rows.size() > 1) {
            throw new IllegalStateException(
                "Duplicate model rows exist for this provider"
            );
        }
        return rows.stream().findFirst();
    }

    public record ModelMetadata(Integer maxInputLength) {}

    public List<ModelRow> findByProviderId(String providerId) {
        return jdbc.query(
            """
            SELECT model_id, name, supports_multimodal,
                supports_image, supports_video, probe_source,
                is_free, max_tokens, max_input_length,
                max_input_length_configured, generate_kwargs,
                relay_reasoning, thinking_enabled, thinking_budget,
                reasoning_effort, thinking_param_style,
                reasoning_effort_options, thinking_budget_range,
                hidden
            FROM provider_models
            WHERE provider_id = :providerId
            """,
            Map.of("providerId", providerId),
            (rs, rowNum) -> new ModelRow(
                rs.getString("model_id"),
                rs.getString("name"),
                rs.getBoolean("supports_multimodal"),
                rs.getBoolean("supports_image"),
                rs.getBoolean("supports_video"),
                rs.getString("probe_source"),
                rs.getBoolean("is_free"),
                rs.getInt("max_tokens"),
                rs.getInt("max_input_length"),
                rs.getBoolean("max_input_length_configured"),
                rs.getString("generate_kwargs"),
                rs.getBoolean("relay_reasoning"),
                nullableBoolean(rs, "thinking_enabled"),
                nullableInteger(rs, "thinking_budget"),
                rs.getString("reasoning_effort"),
                rs.getString("thinking_param_style"),
                rs.getString("reasoning_effort_options"),
                rs.getString("thinking_budget_range"),
                rs.getBoolean("hidden")
            )
        );
    }

    private static Boolean nullableBoolean(
        ResultSet rs,
        String column
    ) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(
        ResultSet rs,
        String column
    ) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record ModelRow(
        String modelId,
        String name,
        Boolean supportsMultimodal,
        Boolean supportsImage,
        Boolean supportsVideo,
        String probeSource,
        Boolean isFree,
        Integer maxTokens,
        Integer maxInputLength,
        Boolean maxInputLengthConfigured,
        String generateKwargs,
        Boolean relayReasoning,
        Boolean thinkingEnabled,
        Integer thinkingBudget,
        String reasoningEffort,
        String thinkingParamStyle,
        String reasoningEffortOptions,
        String thinkingBudgetRange,
        Boolean hidden
    ) {}

    public Optional<DiscoveryRow> findDiscoveryRow(
        String providerId,
        String modelId
    ) {
        List<DiscoveryRow> rows = jdbc.query(
            """
            SELECT id, max_tokens, max_input_length,
                   max_input_length_configured
            FROM provider_models
            WHERE provider_id = :providerId
              AND model_id = :modelId
            LIMIT 2
            """,
            Map.of("providerId", providerId, "modelId", modelId),
            (rs, rowNum) -> new DiscoveryRow(
                rs.getLong("id"),
                rs.getInt("max_tokens"),
                rs.getInt("max_input_length"),
                rs.getBoolean("max_input_length_configured")
            )
        );
        if (rows.size() > 1) {
            throw new IllegalStateException(
                "Duplicate model rows exist for this provider"
            );
        }
        return rows.stream().findFirst();
    }

    public void saveDiscoveredModel(
        String providerId,
        String modelId,
        String name,
        Long existingRowId
    ) {
        if (existingRowId != null) {
            jdbc.update(
                """
                UPDATE provider_models
                SET name = :name, probe_source = 'openai-models'
                WHERE id = :id
                """,
                Map.of("id", existingRowId, "name", name)
            );
            return;
        }

        jdbc.update(
            """
            INSERT INTO provider_models (
                provider_id, model_id, name, probe_source,
                max_tokens, max_input_length,
                max_input_length_configured
            ) VALUES (
                :providerId, :modelId, :name, 'openai-models',
                8192, 131072, FALSE
            )
            """,
            Map.of(
                "providerId", providerId,
                "modelId", modelId,
                "name", name
            )
        );
    }

    public record DiscoveryRow(
        Long id,
        Integer maxTokens,
        Integer maxInputLength,
        Boolean maxInputLengthConfigured
    ) {}

    public void setHidden(
        String providerId,
        String modelId,
        boolean hidden
    ) {
        jdbc.update(
            """
            UPDATE provider_models
            SET hidden = :hidden
            WHERE provider_id = :providerId
            AND model_id = :modelId
            """,
            new MapSqlParameterSource()
                .addValue("providerId", providerId)
                .addValue("modelId", modelId)
                .addValue("hidden", hidden)
        );
    }

    public void updateConfig(
        String providerId,
        String modelId,
        Map<String, Object> updates
    ) {
        StringJoiner assignments = new StringJoiner(", ");
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("providerId", providerId)
            .addValue("modelId", modelId);

        // This fixed allowlist is the set of fields Majo updates.
        for (String column : List.of(
            "max_tokens",
            "max_input_length",
            "generate_kwargs",
            "relay_reasoning",
            "thinking_enabled",
            "thinking_budget",
            "reasoning_effort"
        )) {
            if (updates.containsKey(column)) {
                assignments.add(column + " = :" + column);
                params.addValue(column, updates.get(column));
            }
        }

        if (updates.containsKey("max_input_length")) {
            assignments.add("max_input_length_configured = TRUE");
        }

        if (assignments.length() == 0) {
            return;
        }

        jdbc.update(
            "UPDATE provider_models SET " + assignments
                + " WHERE provider_id = :providerId"
                + " AND model_id = :modelId",
            params
        );
    }
}
