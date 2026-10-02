package io.github.ddxpromax.webcoder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.ddxpromax.webcoder.conversation.ConversationService;
import io.github.ddxpromax.webcoder.conversation.Conversation;


@SpringBootTest
@Import(ApiApplicationTests.PostgresTestConfiguration.class)
class ApiApplicationTests {

	@Test
	void contextLoads() {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresTestConfiguration {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer("postgres:17.11");
		}
	}

	@Autowired
	private ConversationService conversationService;

	@Test
	void createdConversationCanBeListed() {
		Conversation created = conversationService.create();

		assertThat(created.title()).isEqualTo("New conversation");
		assertThat(conversationService.findRecent(100))
			.extracting(Conversation::id)
			.contains(created.id());
	}
}
