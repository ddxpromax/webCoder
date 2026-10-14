package io.github.ddxpromax.webcoder.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
}