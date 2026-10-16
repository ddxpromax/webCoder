package io.github.ddxpromax.webcoder.settings;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EnvVarRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public EnvVarRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EnvVar> findAll() {
        return jdbc.query(
            "SELECT env_key, env_value FROM env_vars ORDER BY env_key",
            Map.of(),
            (result, row) -> new EnvVar(
                result.getString("env_key"),
                result.getString("env_value")
            )
        );
    }

    @Transactional
    public List<EnvVar> replaceAll(Map<String, String> values) {
        lockCollection();
        jdbc.update("DELETE FROM env_vars", Map.of());
        values.forEach((rawKey, value) -> {
            if (rawKey == null || rawKey.isBlank()) {
                return;
            }
            String key = rawKey.trim();
            if (key.length() > 255 || value == null
                || value.length() > 4096) {
                throw new IllegalArgumentException(
                    "Environment variable exceeds the supported length"
                );
            }
            jdbc.update(
                """
                INSERT INTO env_vars (env_key, env_value)
                VALUES (:key, :value)
                ON DUPLICATE KEY UPDATE env_value = :value
                """,
                new MapSqlParameterSource()
                    .addValue("key", key)
                    .addValue("value", value)
            );
        });
        return findAll();
    }

    @Transactional
    public List<EnvVar> delete(String key) {
        lockCollection();
        jdbc.update(
            "DELETE FROM env_vars WHERE env_key = :key",
            Map.of("key", key)
        );
        return findAll();
    }

    private void lockCollection() {
        jdbc.queryForObject(
            "SELECT id FROM settings WHERE id = 1 FOR UPDATE",
            Map.of(), Integer.class
        );
    }

    public record EnvVar(String key, String value) {}
}
