package io.github.ddxpromax.webcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

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
}