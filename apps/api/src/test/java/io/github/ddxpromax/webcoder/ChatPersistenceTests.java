package io.github.ddxpromax.webcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;


@SpringBootTest
@AutoConfigureMockMvc
@Import(ChatPersistenceTests.MySqlTestConfiguration.class)
class ChatPersistenceTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlTestConfiguration {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysqlContainer() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    @Autowired
    private ChatService chatService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void chatMessagesPersistAndLoadThroughMajoShapedApi() throws Exception {
        ChatEntity chat = chatService.create();

        chatService.saveMessages(chat.getId(), List.of(
            Map.of("role", "user",
                "content", "[{\"type\":\"text\",\"text\":\"hello\"}]"),
            Map.of("role", "assistant",
                "content", "[{\"type\":\"text\",\"text\":\"Hi.\"}]")
        ));

        assertThat(chatService.getMessages(chat.getId()))
            .extracting(message -> message.getRole())
            .containsExactly("user", "assistant");

        mockMvc.perform(get("/api/chats/{id}", chat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("idle"))
            .andExpect(jsonPath("$.messages[0].content[0].text").value("hello"))
            .andExpect(jsonPath("$.messages[1].content[0].text").value("Hi."));
    }

    @Test
    void mysqlConnectionsUseReadCommitted() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation())
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    @Test
    void sameAgentSessionReusesChat() {
        String sessionId = java.util.UUID.randomUUID().toString();

        ChatEntity first = chatService.getOrCreateBySession(
            "default",
            sessionId,
            "Explain this repository"
        );
        ChatEntity second = chatService.getOrCreateBySession(
            "default",
            sessionId,
            "A later prompt"
        );

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getSessionId()).isEqualTo(sessionId);
        assertThat(second.getTitle()).isEqualTo("Explain th");
    }

    @Test
    void renamePinArchiveAndUnarchiveFollowChatApi() throws Exception {
        ChatEntity chat = chatService.create();

        mockMvc.perform(patch("/api/chats/{id}", chat.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Repository work\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Repository work"));

        mockMvc.perform(put("/api/chats/{id}", chat.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":\"true\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true));

        mockMvc.perform(post("/api/chats/{id}/archive", chat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(get("/api/chats")
                .param("archived", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(chat.getId())
            ));

        mockMvc.perform(post("/api/chats/{id}/unarchive", chat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void batchArchiveUsesMajoChatIdsPayload() throws Exception {
        ChatEntity first = chatService.create();
        ChatEntity second = chatService.create();

        String body = """
            {"chat_ids":["%s","%s"]}
            """.formatted(first.getId(), second.getId());

        mockMvc.perform(post("/api/chats/actions/batch-archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        assertThat(chatService.getChat(first.getId()).getArchivedAt())
            .isNotNull();
        assertThat(chatService.getChat(second.getId()).getArchivedAt())
            .isNotNull();
    }

    @Test
    void modelCatalogMigrationPreservesMajoRelationships() {
        String providerId = "test-" + java.util.UUID.randomUUID();
        String configName = "config-" + java.util.UUID.randomUUID();

        jdbcTemplate.update(
            "INSERT INTO model_configs (name, api_key) VALUES (?, ?)",
            configName,
            "test-key"
        );

        String defaultModel = jdbcTemplate.queryForObject(
            "SELECT model_name FROM model_configs WHERE name = ?",
            String.class,
            configName
        );
        assertThat(defaultModel).isEqualTo("gpt-4o-mini");

        jdbcTemplate.update(
            "INSERT INTO providers (id, name) VALUES (?, ?)",
            providerId,
            "Test Provider"
        );
        jdbcTemplate.update(
            """
            INSERT INTO provider_models (provider_id, model_id, name)
            VALUES (?, ?, ?)
            """,
            providerId,
            "test-model",
            "Test Model"
        );

        Long providerIndexColumns = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
            AND table_name = 'provider_models'
            AND index_name = 'idx_models_provider'
            AND column_name = 'provider_id'
            """,
            Long.class
        );
        assertThat(providerIndexColumns).isEqualTo(1L);

        Boolean hidden = jdbcTemplate.queryForObject(
            """
            SELECT hidden
            FROM provider_models
            WHERE provider_id = ?
            AND model_id = ?
            """,
            Boolean.class,
            providerId,
            "test-model"
        );
        assertThat(hidden).isFalse();

        Long globalModelRows = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM active_models
            WHERE scope = 'global'
            AND agent_id IS NULL
            """,
            Long.class
        );
        assertThat(globalModelRows).isEqualTo(1L);

        jdbcTemplate.update(
            "DELETE FROM providers WHERE id = ?",
            providerId
        );

        Long remainingModels = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM provider_models
            WHERE provider_id = ?
            """,
            Long.class,
            providerId
        );
        assertThat(remainingModels).isZero();

        jdbcTemplate.update(
            "DELETE FROM model_configs WHERE name = ?",
            configName
        );
    }

    @Test
    void builtInProviderCatalogMatchesMajoSeed() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM providers",
            Long.class
        )).isEqualTo(33L);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM provider_models",
            Long.class
        )).isEqualTo(175L);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT base_url FROM providers WHERE id = ?",
            String.class,
            "ollama"
        )).isEqualTo("http://127.0.0.1:11434");

        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT max_input_length
            FROM provider_models
            WHERE provider_id = ?
            AND model_id = ?
            """,
            Integer.class,
            "openai",
            "gpt-5.2"
        )).isEqualTo(131072);
    }

    @Test
    void activeModelUsesAgentSelectionBeforeGlobalFallback() throws Exception {
        String agentId = "test-" + java.util.UUID.randomUUID();

        try {
            mockMvc.perform(put("/api/models/active")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                        "scope": "global",
                        "provider_id": "openai",
                        "model": "gpt-5.2"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.provider_id")
                    .value("openai"))
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-5.2"))
                .andExpect(jsonPath("$.effective_max_input_length")
                    .value(131072));

            mockMvc.perform(get("/api/models/active")
                    .param("scope", "effective")
                    .param("agent_id", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-5.2"));

            mockMvc.perform(put("/api/models/active")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                        "scope": "agent",
                        "agent_id": "%s",
                        "provider_id": "openai",
                        "model": "gpt-4o"
                        }
                        """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-4o"));

            mockMvc.perform(get("/api/models/active")
                    .param("scope", "effective")
                    .param("agent_id", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-4o"));

            mockMvc.perform(get("/api/models/active")
                    .param("scope", "global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-5.2"));
        } finally {
            jdbcTemplate.update(
                """
                DELETE FROM active_models
                WHERE scope = 'agent'
                AND agent_id = ?
                """,
                agentId
            );
            jdbcTemplate.update(
                """
                UPDATE active_models
                SET provider_id = '', model_id = ''
                WHERE scope = 'global'
                AND agent_id IS NULL
                """
            );
        }
    }

    @Test
    void customProviderUsesMajoModelConfigIdAndContextSize()
        throws Exception {
        String name = "custom-" + java.util.UUID.randomUUID();

        jdbcTemplate.update(
            """
            INSERT INTO model_configs (
                name, api_key, base_url, model_name
            ) VALUES (?, ?, ?, ?)
            """,
            name,
            "test-key",
            "https://example.test/v1",
            "custom-model"
        );

        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM model_configs WHERE name = ?",
            Long.class,
            name
        );

        try {
            mockMvc.perform(put("/api/models/active")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                        "scope": "global",
                        "provider_id": "%s",
                        "model": "custom-model"
                        }
                        """.formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.provider_id")
                    .value(id.toString()))
                .andExpect(jsonPath("$.effective_max_input_length")
                    .value(128000));
        } finally {
            jdbcTemplate.update(
                """
                UPDATE active_models
                SET provider_id = '', model_id = ''
                WHERE scope = 'global'
                AND agent_id IS NULL
                """
            );
            jdbcTemplate.update(
                "DELETE FROM model_configs WHERE id = ?",
                id
            );
        }
    }

    @Test
    void providerListMatchesMajoCatalogShape() throws Exception {
        String name = "custom-" + java.util.UUID.randomUUID();

        jdbcTemplate.update(
            """
            INSERT INTO model_configs (
                name, api_key, base_url, model_name
            ) VALUES (?, ?, ?, ?)
            """,
            name,
            "sk-test-secret",
            "https://example.test/v1",
            "custom-model"
        );

        Long customId = jdbcTemplate.queryForObject(
            "SELECT id FROM model_configs WHERE name = ?",
            Long.class,
            name
        );

        try {
            String response = mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

            com.fasterxml.jackson.databind.JsonNode catalog =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response);

            assertThat(catalog.size()).isEqualTo(34);

            com.fasterxml.jackson.databind.JsonNode openai = null;
            com.fasterxml.jackson.databind.JsonNode custom = null;

            for (com.fasterxml.jackson.databind.JsonNode provider
                : catalog) {
                if ("openai".equals(provider.path("id").asText())) {
                    openai = provider;
                }
                if (customId.toString().equals(
                    provider.path("id").asText()
                )) {
                    custom = provider;
                }
            }

            assertThat(openai).isNotNull();
            assertThat(openai.path("models").size()).isEqualTo(11);
            assertThat(openai.path("extra_models").isArray()).isTrue();

            assertThat(custom).isNotNull();
            assertThat(custom.path("name").asText()).isEqualTo(name);
            assertThat(custom.path("api_key").asText())
                .isEqualTo("****");
            assertThat(custom.path("models").get(0)
                .path("id").asText())
                .isEqualTo("custom-model");
            assertThat(custom.path("models").get(0)
                .path("max_input_length").asInt())
                .isEqualTo(128000);

            assertThat(catalog.get(catalog.size() - 1)
                .path("id").asText())
                .isEqualTo(customId.toString());
        } finally {
            jdbcTemplate.update(
                "DELETE FROM model_configs WHERE id = ?",
                customId
            );
        }
    }

    @Test
    void builtInProviderConfigUpdatesOnlySuppliedFields()
        throws Exception {
        String originalApiKey = jdbcTemplate.queryForObject(
            "SELECT api_key FROM providers WHERE id = ?",
            String.class,
            "openai"
        );
        String originalBaseUrl = jdbcTemplate.queryForObject(
            "SELECT base_url FROM providers WHERE id = ?",
            String.class,
            "openai"
        );
        String originalChatModel = jdbcTemplate.queryForObject(
            "SELECT chat_model FROM providers WHERE id = ?",
            String.class,
            "openai"
        );

        try {
            mockMvc.perform(put("/api/models/openai/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                        "api_key": "sk-test-config",
                        "base_url": "https://example.test/v1"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("openai"))
                .andExpect(jsonPath("$.api_key")
                    .value("sk-test-config"))
                .andExpect(jsonPath("$.base_url")
                    .value("https://example.test/v1"))
                .andExpect(jsonPath("$.chat_model")
                    .value(originalChatModel));

            assertThat(jdbcTemplate.queryForObject(
                "SELECT api_key FROM providers WHERE id = ?",
                String.class,
                "openai"
            )).isEqualTo("sk-test-config");

            mockMvc.perform(put("/api/models/openai/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"api_key": ""}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.api_key").value(""))
                .andExpect(jsonPath("$.base_url")
                    .value("https://example.test/v1"));
        } finally {
            jdbcTemplate.update(
                """
                UPDATE providers
                SET api_key = ?,
                    base_url = ?,
                    chat_model = ?
                WHERE id = ?
                """,
                originalApiKey,
                originalBaseUrl,
                originalChatModel,
                "openai"
            );
        }

        mockMvc.perform(put("/api/models/not-a-provider/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"api_key": "x"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not found"));
    }

    @Test
    void customProviderCreationAndDeletionMatchMajo()
        throws Exception {
        String name = "custom-" + java.util.UUID.randomUUID();

        try {
            String response = mockMvc.perform(
                    post("/api/models/custom-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                            "id": "client-supplied-id",
                            "name": "%s",
                            "default_base_url":
                                "https://example.test/v1",
                            "api_key": "sk-test-secret",
                            "chat_model": "custom-model"
                            }
                            """.formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.base_url")
                    .value("https://example.test/v1"))
                .andExpect(jsonPath("$.api_key")
                    .value("****"))
                .andExpect(jsonPath("$.models[0].id")
                    .value("custom-model"))
                .andReturn()
                .getResponse()
                .getContentAsString();

            com.fasterxml.jackson.databind.JsonNode created =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response);

            String providerId = created.path("id").asText();
            assertThat(providerId).matches("[0-9]+");
            assertThat(providerId).isNotEqualTo(
                "client-supplied-id"
            );

            assertThat(jdbcTemplate.queryForObject(
                "SELECT api_key FROM model_configs WHERE id = ?",
                String.class,
                Long.parseLong(providerId)
            )).isEqualTo("sk-test-secret");

            mockMvc.perform(delete(
                    "/api/models/custom-providers/{id}",
                    providerId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_configs WHERE id = ?",
                Long.class,
                Long.parseLong(providerId)
            )).isZero();
        } finally {
            jdbcTemplate.update(
                "DELETE FROM model_configs WHERE name = ?",
                name
            );
        }
    }

    @Test
    void builtInModelConfigurationAndVisibilityMatchMajo()
        throws Exception {
        String providerId = "test-" + java.util.UUID.randomUUID();

        jdbcTemplate.update(
            "INSERT INTO providers (id, name) VALUES (?, ?)",
            providerId,
            "Test Provider"
        );
        jdbcTemplate.update(
            """
            INSERT INTO provider_models (
                provider_id, model_id, name
            ) VALUES (?, ?, ?)
            """,
            providerId,
            "test-model",
            "Test Model"
        );

        try {
            mockMvc.perform(put(
                    "/api/models/{providerId}/models/{modelId}/config",
                    providerId,
                    "test-model"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "max_tokens": 4096,
                    "max_input_length": 16384,
                    "generate_kwargs": {"temperature": 0.5},
                    "relay_reasoning": false,
                    "thinking_enabled": null,
                    "thinking_budget": 2048,
                    "reasoning_effort": "high"
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].max_tokens")
                    .value(4096))
                .andExpect(jsonPath(
                    "$.models[0].max_input_length_configured"
                ).value(true));

            assertThat(jdbcTemplate.queryForObject(
                """
                SELECT generate_kwargs
                FROM provider_models
                WHERE provider_id = ? AND model_id = ?
                """,
                String.class,
                providerId,
                "test-model"
            )).isEqualTo("{\"temperature\":0.5}");

            mockMvc.perform(put(
                    "/api/models/{providerId}/models/{modelId}/visibility",
                    providerId,
                    "test-model"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hidden\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].hidden")
                    .value(true));

            assertThat(jdbcTemplate.queryForObject(
                """
                SELECT hidden
                FROM provider_models
                WHERE provider_id = ? AND model_id = ?
                """,
                Boolean.class,
                providerId,
                "test-model"
            )).isTrue();

            // Majo does not add/remove models for built-in providers here.
            mockMvc.perform(post(
                    "/api/models/{providerId}/models",
                    providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"model_id\":\"another\",\"name\":\"Another\"}"
                ))
                .andExpect(status().isOk())
                .andExpect(content().string("{}"));

            mockMvc.perform(delete(
                    "/api/models/{providerId}/models/{modelId}",
                    providerId,
                    "test-model"
                ))
                .andExpect(status().isOk())
                .andExpect(content().string("{}"));

            mockMvc.perform(put(
                    "/api/models/{providerId}/models/{modelId}/config",
                    providerId,
                    "missing-model"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Model 'missing-model' not found in provider '"
                        + providerId + "'"
                ));
        } finally {
            jdbcTemplate.update(
                "DELETE FROM providers WHERE id = ?",
                providerId
            );
        }
    }

    @Test
    void customProviderModelEndpointsUseSingleModelName()
        throws Exception {
        String name = "custom-" + java.util.UUID.randomUUID();

        String response = mockMvc.perform(
                post("/api/models/custom-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"" + name + "\"}"
                    ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String providerId =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("id")
                .asText();

        try {
            mockMvc.perform(post(
                    "/api/models/{providerId}/models",
                    providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model_id\":\"custom-alpha\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id")
                    .value("custom-alpha"));

            // Majo's frontend sends "id"; its backend ignores that field.
            mockMvc.perform(post(
                    "/api/models/{providerId}/models",
                    providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"id\":\"ignored-model\",\"name\":\"Ignored\"}"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id")
                    .value("custom-alpha"));

            mockMvc.perform(put(
                    "/api/models/{providerId}/models/{modelId}/config",
                    providerId,
                    "custom-alpha"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model_name\":\"custom-beta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id")
                    .value("custom-beta"));

            mockMvc.perform(delete(
                    "/api/models/{providerId}/models/{modelId}",
                    providerId,
                    "custom-beta"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id")
                    .value("gpt-4o-mini"));

            assertThat(jdbcTemplate.queryForObject(
                "SELECT model_name FROM model_configs WHERE id = ?",
                String.class,
                Long.parseLong(providerId)
            )).isEqualTo("gpt-4o-mini");
        } finally {
            jdbcTemplate.update(
                "DELETE FROM model_configs WHERE id = ?",
                Long.parseLong(providerId)
            );
        }
    }

    @Test
    void connectionAndDiscoveryMatchMajo() throws Exception {
        String providerId = "test-" + java.util.UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO providers (id, name) VALUES (?, ?)",
            providerId,
            "Discovery Test Provider"
        );

        var server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0),
            0
        );
        var authorization =
            new java.util.concurrent.atomic.AtomicReference<String>();
        byte[] responseBody = """
            {
              "data": [
                {"id": "discovered-alpha", "name": "Alpha"},
                {"name": "Missing ID"}
              ]
            }
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/v1/models", exchange -> {
            authorization.set(
                exchange.getRequestHeaders().getFirst("Authorization")
            );
            exchange.getResponseHeaders().set(
                "Content-Type", "application/json"
            );
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });

        try {
            server.start();
            String baseUrl = "http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1";
            String requestBody = """
                {"base_url":"%s","api_key":"test-key"}
                """.formatted(baseUrl);

            mockMvc.perform(post(
                    "/api/models/{providerId}/test", providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                    .value("Connection successful"));
            assertThat(authorization.get())
                .isEqualTo("Bearer test-key");

            mockMvc.perform(post(
                    "/api/models/{providerId}/models/test", providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "base_url":"%s",
                      "api_key":"test-key",
                      "model_id":"any-model"
                    }
                    """.formatted(baseUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(
                    "Model 'any-model' connection successful"
                ));

            mockMvc.perform(post(
                    "/api/models/{providerId}/discover", providerId
                )
                .param("save", "false")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models.length()").value(1))
                .andExpect(jsonPath("$.models[0].id")
                    .value("discovered-alpha"))
                .andExpect(jsonPath("$.added_count").value(0));
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_models WHERE provider_id = ?",
                Long.class,
                providerId
            )).isZero();

            mockMvc.perform(post(
                    "/api/models/{providerId}/discover", providerId
                )
                .param("save", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].max_tokens")
                    .value(8192))
                .andExpect(jsonPath("$.added_count").value(1));
            assertThat(jdbcTemplate.queryForObject(
                """
                SELECT probe_source FROM provider_models
                WHERE provider_id = ? AND model_id = ?
                """,
                String.class,
                providerId,
                "discovered-alpha"
            )).isEqualTo("openai-models");

            jdbcTemplate.update(
                """
                UPDATE provider_models
                SET max_tokens = 4096,
                    max_input_length = 32768,
                    max_input_length_configured = TRUE
                WHERE provider_id = ? AND model_id = ?
                """,
                providerId,
                "discovered-alpha"
            );
            mockMvc.perform(post(
                    "/api/models/{providerId}/discover", providerId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added_count").value(1))
                .andExpect(jsonPath("$.models[0].max_tokens")
                    .value(4096))
                .andExpect(jsonPath(
                    "$.models[0].max_input_length_configured"
                ).value(true));
            assertThat(jdbcTemplate.queryForObject(
                """
                SELECT max_input_length FROM provider_models
                WHERE provider_id = ? AND model_id = ?
                """,
                Integer.class,
                providerId,
                "discovered-alpha"
            )).isEqualTo(32768);
        } finally {
            server.stop(0);
            jdbcTemplate.update(
                "DELETE FROM providers WHERE id = ?",
                providerId
            );
        }
    }

    @Test
    void multimodalProbeUsesMajoModelIdHeuristic() throws Exception {
        mockMvc.perform(post(
                "/api/models/any-provider/models/gpt-4o-vision"
                    + "/probe-multimodal"
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.supports_image").value(true))
            .andExpect(jsonPath("$.supports_video").value(false))
            .andExpect(jsonPath("$.supports_multimodal").value(true))
            .andExpect(jsonPath("$.image_message").value(""))
            .andExpect(jsonPath("$.video_message")
                .value("Model does not support video input"));

        mockMvc.perform(post("/api/models/nonexistent/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message")
                .value("base_url is required"))
            .andExpect(jsonPath("$.models.length()").value(0))
            .andExpect(jsonPath("$.added_count").value(0));
    }

    @Test
    void majoSettingsPersistOnlySuppliedFields() throws Exception {
        String originalBaseUrl = jdbcTemplate.queryForObject(
            "SELECT base_url FROM settings WHERE id = 1",
            String.class
        );
        String originalWorkspace = jdbcTemplate.queryForObject(
            "SELECT workspace FROM settings WHERE id = 1",
            String.class
        );
        String originalModel = jdbcTemplate.queryForObject(
            "SELECT model_name FROM settings WHERE id = 1",
            String.class
        );
        try {
            mockMvc.perform(post("/api/settings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "baseUrl":"https://example.test/v1",
                          "workspace":"/tmp/majo-workspace"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl")
                    .value("https://example.test/v1"))
                .andExpect(jsonPath("$.workspace")
                    .value("/tmp/majo-workspace"))
                .andExpect(jsonPath("$.modelName")
                    .value(originalModel));

            assertThat(jdbcTemplate.queryForObject(
                "SELECT workspace FROM settings WHERE id = 1",
                String.class
            )).isEqualTo("/tmp/majo-workspace");

            mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace")
                    .value("/tmp/majo-workspace"));
        } finally {
            jdbcTemplate.update(
                """
                UPDATE settings
                SET base_url = ?, workspace = ?
                WHERE id = 1
                """,
                originalBaseUrl,
                originalWorkspace
            );
        }
    }

    @Test
    void majoThemeValidatesNormalizesAndResets() throws Exception {
        String originalAccent = jdbcTemplate.queryForObject(
            "SELECT theme_accent FROM settings WHERE id = 1",
            String.class
        );
        String originalDark = jdbcTemplate.queryForObject(
            "SELECT theme_accent_dark FROM settings WHERE id = 1",
            String.class
        );
        try {
            mockMvc.perform(put("/api/config/theme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "accent":" #AABBCC ",
                          "accent_dark":"#102030"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accent")
                    .value("#aabbcc"))
                .andExpect(jsonPath("$.accent_dark")
                    .value("#102030"));

            mockMvc.perform(put("/api/config/theme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accent\":\"red\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                    .value("accent must be #rrggbb, got: red"));

            mockMvc.perform(delete("/api/config/theme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accent").value(""))
                .andExpect(jsonPath("$.accent_dark").value(""));

            assertThat(jdbcTemplate.queryForObject(
                "SELECT theme_accent FROM settings WHERE id = 1",
                String.class
            )).isNull();
        } finally {
            jdbcTemplate.update(
                """
                UPDATE settings
                SET theme_accent = ?, theme_accent_dark = ?
                WHERE id = 1
                """,
                originalAccent,
                originalDark
            );
        }
    }
}
