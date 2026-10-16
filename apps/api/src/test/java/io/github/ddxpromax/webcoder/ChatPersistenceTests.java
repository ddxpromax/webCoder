package io.github.ddxpromax.webcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.TokenUsageService;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;


@SpringBootTest
@AutoConfigureMockMvc
@Import(ChatPersistenceTests.MySqlTestConfiguration.class)
class ChatPersistenceTests {
    private static final java.nio.file.Path agentWorkingDir =
        java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "webcoder-test-" + java.util.UUID.randomUUID()
        );

    @DynamicPropertySource
    static void agentWorkingDirectory(DynamicPropertyRegistry registry) {
        registry.add(
            "majo.working-dir",
            () -> agentWorkingDir.toString()
        );
    }

    @AfterAll
    static void deleteAgentWorkingDirectory() throws Exception {
        if (!java.nio.file.Files.exists(agentWorkingDir)) {
            return;
        }
        try (var paths = java.nio.file.Files.walk(agentWorkingDir)) {
            for (var path : paths.sorted(
                java.util.Comparator.reverseOrder()
            ).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

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

    @Autowired
    private ModelRoutingService modelRouting;

    @Autowired
    private AgentStore agents;

    @Autowired
    private TokenUsageService tokenUsage;

    @Test
    void environmentVariablesReplaceAndDeleteAsOneCollection()
        throws Exception {
        mockMvc.perform(put("/api/envs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"SECOND\":\"two\",\"FIRST\":\"one\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("FIRST"))
            .andExpect(jsonPath("$[1].value").value("two"));
        mockMvc.perform(put("/api/envs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"THIRD\":\"three\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("THIRD"))
            .andExpect(jsonPath("$[1]").doesNotExist());
        String oversized = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("INVALID", "x".repeat(4097)));
        mockMvc.perform(put("/api/envs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/envs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("THIRD"));
        mockMvc.perform(delete("/api/envs/THIRD"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    void inboxEventsUseIndexedMysqlStorageAndDeleteUnreferencedTrace()
        throws Exception {
        String runId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO inbox_traces (run_id, agent_id, status, meta, events, created_at, updated_at)
            VALUES (?, 'default', 'completed', JSON_OBJECT(), JSON_ARRAY(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """, runId);
        String eventId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO inbox_events (id, agent_id, source_type, source_id, event_type, status,
              severity, title, body, payload, is_read, created_at, updated_at)
            VALUES (?, 'default', 'cron', ?, 'job_finished', 'completed', 'info', 'Scheduled job',
              'Finished', JSON_OBJECT('run_id', ?), FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """, eventId, runId, runId);

        mockMvc.perform(get("/api/console/inbox/events")
                .param("agent_id", "default").param("unread_only", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[?(@.id=='" + eventId + "')].payload.run_id")
                .value(org.hamcrest.Matchers.contains(runId)));
        mockMvc.perform(post("/api/console/inbox/read")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_ids\":[\"" + eventId + "\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        mockMvc.perform(delete("/api/console/inbox/events/{id}", eventId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.trace_deleted").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_traces WHERE run_id = ?", Integer.class, runId))
            .isZero();
    }

    @Test
    void agentSkillEndpointsPersistWorkspaceManifest() throws Exception {
        String skillName = "review_" + java.util.UUID.randomUUID()
            .toString().replace("-", "");
        String content = "---\nname: " + skillName
            + "\ndescription: Review a code change\n---\n"
            + "Read the diff and describe any defects.\n";
        String body = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of(
                "name", skillName,
                "content", content,
                "enable", true
            ));

        mockMvc.perform(post("/api/agents/default/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(skillName));
        mockMvc.perform(get("/api/agents/default/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name=='" + skillName
                + "')].enabled").value(
                    org.hamcrest.Matchers.contains(true)
                ));
        java.nio.file.Path reference = agents
            .workspaceDirForAgent("default")
            .resolve("skills").resolve(skillName)
            .resolve("references/guide.md");
        java.nio.file.Files.createDirectories(
            reference.getParent()
        );
        java.nio.file.Files.writeString(reference, "Read tests.");
        mockMvc.perform(get(
                "/api/agents/default/skills/{name}/files/references/guide.md",
                skillName
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("Read tests."));
        mockMvc.perform(post("/api/agents/default/skills/{name}/disable",
                skillName))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/agents/default/skills/{name}",
                skillName))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void agentSkillZipUploadEndpointImportsTheBundle()
        throws Exception {
        String name = "zip_" + java.util.UUID.randomUUID()
            .toString().replace("-", "");
        java.io.ByteArrayOutputStream bytes =
            new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip =
                 new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(
                "SKILL.md"
            ));
            zip.write(("---\nname: " + name
                + "\ndescription: A ZIP imported skill\n---\n"
                + "Follow the task.\n").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                ));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
            "file", "skill.zip", "application/zip",
            bytes.toByteArray()
        );

        mockMvc.perform(multipart(
                "/api/agents/default/skills/upload"
            ).file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.imported[0]").value(name));
    }

    @Test
    void tokenUsageRecordsAndFiltersUseMySql() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        String agentId = "usage-agent-" + suffix;
        String provider = "usage-provider-" + suffix;
        String model = "usage-model-" + suffix;
        String today = java.time.LocalDate.now().toString();
        tokenUsage.record(
            agentId, null, provider, model, 80, 20, 1200
        );
        tokenUsage.record(
            agentId, null, provider, model, 40, 10, 900
        );

        mockMvc.perform(get("/api/token-usage/details")
                .param("start_date", today)
                .param("end_date", today)
                .param("provider", provider)
                .param("model", model))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].prompt_tokens").value(120))
            .andExpect(jsonPath("$[0].completion_tokens").value(30))
            .andExpect(jsonPath("$[0].call_count").value(2));

        mockMvc.perform(get("/api/token-usage")
                .param("start_date", today)
                .param("end_date", today)
                .param("provider", provider)
                .param("model", model))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_prompt_tokens").value(120))
            .andExpect(jsonPath("$.total_completion_tokens").value(30))
            .andExpect(jsonPath("$.total_calls").value(2));

        assertThat(tokenUsage.details(
            today, today, null, null
        )).anySatisfy(record -> {
            assertThat(record.get("provider_id")).isEqualTo(provider);
            assertThat(record.get("prompt_tokens")).isEqualTo(120L);
        });
        assertThat(tokenUsage.agentStatsSince(today))
            .filteredOn(record -> agentId.equals(
                record.get("agent_id")
            ))
            .singleElement()
            .satisfies(record -> {
                assertThat(record.get("input_tokens"))
                    .isEqualTo(120L);
                assertThat(record.get("turns"))
                    .isEqualTo(2L);
            });
    }

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
    void chatLookupIndexesSupportAgentAndSessionQueries() {
        List<String> names = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'chats'
              AND index_name IN (
                'idx_chats_agent_archived_updated',
                'idx_chats_session_updated',
                'idx_chats_archived_updated'
              )
            """, String.class
        );
        assertThat(names).containsExactlyInAnyOrder(
            "idx_chats_agent_archived_updated",
            "idx_chats_session_updated",
            "idx_chats_archived_updated"
        );
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

    @Test
    void activeProviderBuildsAgentScopeModelWithMajoContextWindow() {
        var model = modelRouting.buildOpenAIChatModel(
            "openai", "gpt-5.2"
        );
        assertThat(model.getModelName()).isEqualTo("gpt-5.2");
        assertThat(model.getContextWindowSize())
            .isEqualTo(131072);

        var fallback = modelRouting.buildOpenAIChatModel(
            "not-a-provider", "fallback-model"
        );
        assertThat(fallback.getModelName())
            .isEqualTo("fallback-model");
    }

    @Test
    void agentModelSelectionSyncsAgentProfile() throws Exception {
        String agentId = "test-"
            + java.util.UUID.randomUUID().toString().substring(0, 8);
        agents.createAgent(Map.of("id", agentId));
        try {
            mockMvc.perform(put("/api/models/active")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "scope":"agent",
                          "agent_id":"%s",
                          "provider_id":"openai",
                          "model":"gpt-5.2"
                        }
                        """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_llm.model")
                    .value("gpt-5.2"));

            @SuppressWarnings("unchecked")
            Map<String, Object> selected = (Map<String, Object>)
                agents.getProfile(agentId).get("active_model");
            assertThat(selected).containsEntry(
                "provider_id", "openai"
            ).containsEntry("model", "gpt-5.2");
        } finally {
            jdbcTemplate.update(
                """
                DELETE FROM active_models
                WHERE scope = 'agent' AND agent_id = ?
                """,
                agentId
            );
            agents.deleteAgent(agentId);
        }
    }
}
