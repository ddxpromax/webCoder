package io.github.ddxpromax.webcoder.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ProviderSeedData implements ApplicationRunner {
    private static final String INSERT_PROVIDER = """
        INSERT INTO providers (
            id, name, base_url, api_key, chat_model,
            api_key_prefix, api_key_prefixes, is_local, freeze_url,
            require_api_key, is_custom, support_model_discovery,
            support_connection_check, auth_mode, supports_oauth,
            is_free_tier, provider_group, provider_group_name,
            provider_variant, thinking_param_style,
            reasoning_effort_options, thinking_budget_range,
            generate_kwargs, custom_headers, extra_fields
        ) VALUES (
            :id, :name, :baseUrl, :apiKey, :chatModel,
            :apiKeyPrefix, :apiKeyPrefixes, :isLocal, :freezeUrl,
            :requireApiKey, :isCustom, :supportModelDiscovery,
            :supportConnectionCheck, :authMode, :supportsOauth,
            :isFreeTier, :providerGroup, :providerGroupName,
            :providerVariant, :thinkingParamStyle,
            :reasoningEffortOptions, :thinkingBudgetRange,
            :generateKwargs, :customHeaders, :extraFields
        )
        """;

    private static final String INSERT_MODEL = """
        INSERT INTO provider_models (
            provider_id, model_id, name, supports_multimodal,
            supports_image, supports_video, probe_source, is_free,
            max_tokens, max_input_length, max_input_length_configured,
            relay_reasoning, thinking_enabled, thinking_budget,
            reasoning_effort, thinking_param_style, generate_kwargs,
            reasoning_effort_options, thinking_budget_range
        ) VALUES (
            :providerId, :modelId, :name, :supportsMultimodal,
            :supportsImage, :supportsVideo, :probeSource, :isFree,
            :maxTokens, :maxInputLength, :maxInputLengthConfigured,
            :relayReasoning, :thinkingEnabled, :thinkingBudget,
            :reasoningEffort, :thinkingParamStyle, :generateKwargs,
            :reasoningEffortOptions, :thinkingBudgetRange
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public ProviderSeedData(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        JsonNode providers;
        try (InputStream input = new ClassPathResource(
            "config/providers.json"
        ).getInputStream()) {
            providers = mapper.readTree(input);
        }

        if (providers == null || !providers.isArray()) {
            throw new IllegalStateException(
                "config/providers.json must contain a provider array"
            );
        }

        transactions.executeWithoutResult(status -> {
            Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM providers",
                Map.of(),
                Long.class
            );
            if (existing != null && existing > 0) {
                return;
            }

            for (JsonNode provider : providers) {
                String providerId = text(provider, "id");
                if (providerId.isBlank()) {
                    throw new IllegalStateException(
                        "Provider catalog contains an empty provider id"
                    );
                }

                jdbc.update(INSERT_PROVIDER, new MapSqlParameterSource()
                    .addValue("id", providerId)
                    .addValue("name", text(provider, "name"))
                    .addValue("baseUrl", text(provider, "base_url"))
                    .addValue("apiKey", text(provider, "api_key"))
                    .addValue("chatModel", text(provider, "chat_model"))
                    .addValue("apiKeyPrefix", text(provider, "api_key_prefix"))
                    .addValue("apiKeyPrefixes", json(provider, "api_key_prefixes"))
                    .addValue("isLocal", flag(provider, "is_local"))
                    .addValue("freezeUrl", flag(provider, "freeze_url"))
                    .addValue("requireApiKey", flag(provider, "require_api_key"))
                    .addValue("isCustom", flag(provider, "is_custom"))
                    .addValue("supportModelDiscovery",
                        flag(provider, "support_model_discovery"))
                    .addValue("supportConnectionCheck",
                        flag(provider, "support_connection_check"))
                    .addValue("authMode", text(provider, "auth_mode"))
                    .addValue("supportsOauth", flag(provider, "supports_oauth"))
                    .addValue("isFreeTier", flag(provider, "is_free_tier"))
                    .addValue("providerGroup", text(provider, "provider_group"))
                    .addValue("providerGroupName",
                        text(provider, "provider_group_name"))
                    .addValue("providerVariant",
                        text(provider, "provider_variant"))
                    .addValue("thinkingParamStyle",
                        nullableText(provider, "thinking_param_style"))
                    .addValue("reasoningEffortOptions",
                        json(provider, "reasoning_effort_options"))
                    .addValue("thinkingBudgetRange",
                        json(provider, "thinking_budget_range"))
                    .addValue("generateKwargs",
                        json(provider, "generate_kwargs"))
                    .addValue("customHeaders",
                        json(provider, "custom_headers"))
                    .addValue("extraFields", json(provider, "meta"))
                );

                for (JsonNode model : provider.path("models")) {
                    jdbc.update(INSERT_MODEL, new MapSqlParameterSource()
                        .addValue("providerId", providerId)
                        .addValue("modelId", text(model, "id"))
                        .addValue("name", text(model, "name"))
                        .addValue("supportsMultimodal",
                            flag(model, "supports_multimodal"))
                        .addValue("supportsImage",
                            flag(model, "supports_image"))
                        .addValue("supportsVideo",
                            flag(model, "supports_video"))
                        .addValue("probeSource",
                            nullableText(model, "probe_source"))
                        .addValue("isFree", flag(model, "is_free"))
                        .addValue("maxTokens",
                            number(model, "max_tokens", 8192))
                        .addValue("maxInputLength",
                            number(model, "max_input_length", 131072))
                        .addValue("maxInputLengthConfigured",
                            flag(model, "max_input_length_configured"))
                        .addValue("relayReasoning",
                            flag(model, "relay_reasoning"))
                        .addValue("thinkingEnabled",
                            nullableFlag(model, "thinking_enabled"))
                        .addValue("thinkingBudget",
                            nullableNumber(model, "thinking_budget"))
                        .addValue("reasoningEffort",
                            nullableText(model, "reasoning_effort"))
                        .addValue("thinkingParamStyle",
                            nullableText(model, "thinking_param_style"))
                        .addValue("generateKwargs",
                            json(model, "generate_kwargs"))
                        .addValue("reasoningEffortOptions",
                            json(model, "reasoning_effort_options"))
                        .addValue("thinkingBudgetRange",
                            json(model, "thinking_budget_range"))
                    );
                }
            }
        });
    }

    private static String text(JsonNode object, String key) {
        return object.path(key).asText("");
    }

    private static String nullableText(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String json(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() ? null : value.toString();
    }

    private static boolean flag(JsonNode object, String key) {
        return object.path(key).asBoolean(false);
    }

    private static Boolean nullableFlag(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static int number(JsonNode object, String key, int fallback) {
        return object.path(key).asInt(fallback);
    }

    private static Integer nullableNumber(JsonNode object, String key) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() ? null : value.asInt();
    }
}