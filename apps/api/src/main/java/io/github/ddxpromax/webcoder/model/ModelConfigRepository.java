package io.github.ddxpromax.webcoder.model;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

@Repository
public class ModelConfigRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ModelConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CustomModel> findById(long id) {
        return jdbc.query(
            """
            SELECT base_url, api_key, model_name
            FROM model_configs
            WHERE id = :id
            """,
            Map.of("id", id),
            (rs, rowNum) -> new CustomModel(
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("model_name")
            )
        ).stream().findFirst();
    }

    public record CustomModel(
        String baseUrl,
        String apiKey,
        String modelName
    ) {}

    public List<CustomProviderRow> findAll() {
        return jdbc.query(
            """
            SELECT id, name, api_key, base_url, model_name
            FROM model_configs
            """,
            Map.of(),
            (rs, rowNum) -> new CustomProviderRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("api_key"),
                rs.getString("base_url"),
                rs.getString("model_name")
            )
        );
    }

    public record CustomProviderRow(
        Long id,
        String name,
        String apiKey,
        String baseUrl,
        String modelName
    ) {}

    public CustomProviderRow create(
        String name,
        String apiKey,
        String baseUrl,
        String modelName
    ) {
        KeyHolder keys = new GeneratedKeyHolder();

        jdbc.update(
            """
            INSERT INTO model_configs (
                name, api_key, base_url, model_name
            ) VALUES (
                :name, :apiKey, :baseUrl, :modelName
            )
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("apiKey", apiKey)
                .addValue("baseUrl", baseUrl)
                .addValue("modelName", modelName),
            keys,
            new String[] {"id"}
        );

        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException(
                "Custom provider insert returned no id"
            );
        }

        return new CustomProviderRow(
            id.longValue(),
            name,
            apiKey,
            baseUrl,
            modelName
        );
    }

    public void deleteById(long id) {
        jdbc.update(
            "DELETE FROM model_configs WHERE id = :id",
            Map.of("id", id)
        );
    }
}