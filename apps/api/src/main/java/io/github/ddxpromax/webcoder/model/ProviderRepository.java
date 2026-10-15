package io.github.ddxpromax.webcoder.model;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    public List<ProviderRow> findAllByOrderByNameAsc() {
        return jdbc.query(
            """
            SELECT id, name, base_url, api_key, chat_model,
                api_key_prefix, api_key_prefixes, is_local,
                freeze_url, require_api_key, is_custom,
                support_model_discovery, support_connection_check,
                auth_mode, supports_oauth, oauth_connected,
                is_free_tier, provider_group, provider_group_name,
                provider_variant, thinking_param_style,
                reasoning_effort_options, thinking_budget_range,
                generate_kwargs, custom_headers, extra_fields
            FROM providers
            ORDER BY name ASC
            """,
            Map.of(),
            ProviderRepository::mapProvider
        );
    }

    public record ProviderRow(
        String id,
        String name,
        String baseUrl,
        String apiKey,
        String chatModel,
        String apiKeyPrefix,
        String apiKeyPrefixes,
        boolean isLocal,
        boolean freezeUrl,
        boolean requireApiKey,
        boolean isCustom,
        boolean supportModelDiscovery,
        boolean supportConnectionCheck,
        String authMode,
        boolean supportsOauth,
        boolean oauthConnected,
        boolean isFreeTier,
        String providerGroup,
        String providerGroupName,
        String providerVariant,
        String thinkingParamStyle,
        String reasoningEffortOptions,
        String thinkingBudgetRange,
        String generateKwargs,
        String customHeaders,
        String extraFields
    ) {}

    public Optional<ProviderRow> findFullById(String providerId) {
        return jdbc.query(
            """
            SELECT id, name, base_url, api_key, chat_model,
                api_key_prefix, api_key_prefixes, is_local,
                freeze_url, require_api_key, is_custom,
                support_model_discovery, support_connection_check,
                auth_mode, supports_oauth, oauth_connected,
                is_free_tier, provider_group, provider_group_name,
                provider_variant, thinking_param_style,
                reasoning_effort_options, thinking_budget_range,
                generate_kwargs, custom_headers, extra_fields
            FROM providers
            WHERE id = :providerId
            """,
            Map.of("providerId", providerId),
            ProviderRepository::mapProvider
        ).stream().findFirst();
    }

    public void updateConfig(
        String providerId,
        ProviderConfigUpdate update
    ) {
        jdbc.update(
            """
            UPDATE providers
            SET api_key = CASE
                    WHEN :hasApiKey THEN :apiKey
                    ELSE api_key
                END,
                base_url = CASE
                    WHEN :hasBaseUrl THEN :baseUrl
                    ELSE base_url
                END,
                chat_model = CASE
                    WHEN :hasChatModel THEN :chatModel
                    ELSE chat_model
                END
            WHERE id = :providerId
            """,
            Map.of(
                "providerId", providerId,
                "hasApiKey", update.hasApiKey(),
                "apiKey", update.apiKey(),
                "hasBaseUrl", update.hasBaseUrl(),
                "baseUrl", update.baseUrl(),
                "hasChatModel", update.hasChatModel(),
                "chatModel", update.chatModel()
            )
        );
    }

    private static ProviderRow mapProvider(
        ResultSet rs,
        int rowNum
    ) throws SQLException {
        return new ProviderRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("base_url"),
            rs.getString("api_key"),
            rs.getString("chat_model"),
            rs.getString("api_key_prefix"),
            rs.getString("api_key_prefixes"),
            rs.getBoolean("is_local"),
            rs.getBoolean("freeze_url"),
            rs.getBoolean("require_api_key"),
            rs.getBoolean("is_custom"),
            rs.getBoolean("support_model_discovery"),
            rs.getBoolean("support_connection_check"),
            rs.getString("auth_mode"),
            rs.getBoolean("supports_oauth"),
            rs.getBoolean("oauth_connected"),
            rs.getBoolean("is_free_tier"),
            rs.getString("provider_group"),
            rs.getString("provider_group_name"),
            rs.getString("provider_variant"),
            rs.getString("thinking_param_style"),
            rs.getString("reasoning_effort_options"),
            rs.getString("thinking_budget_range"),
            rs.getString("generate_kwargs"),
            rs.getString("custom_headers"),
            rs.getString("extra_fields")
        );
    }

    public record ProviderConfigUpdate(
        boolean hasApiKey,
        String apiKey,
        boolean hasBaseUrl,
        String baseUrl,
        boolean hasChatModel,
        String chatModel
    ) {}
}