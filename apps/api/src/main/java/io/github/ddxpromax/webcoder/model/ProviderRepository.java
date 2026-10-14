package io.github.ddxpromax.webcoder.model;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProviderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProviderConnection> findById(String providerId) {
        return jdbc.query(
            """
            SELECT base_url, api_key, chat_model
            FROM providers
            WHERE id = :providerId
            """,
            Map.of("providerId", providerId),
            (rs, rowNum) -> new ProviderConnection(
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("chat_model")
            )
        ).stream().findFirst();
    }

    public record ProviderConnection(
        String baseUrl,
        String apiKey,
        String chatModel
    ) {}
}