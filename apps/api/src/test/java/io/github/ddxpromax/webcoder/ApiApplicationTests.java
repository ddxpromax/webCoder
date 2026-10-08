package io.github.ddxpromax.webcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.ddxpromax.webcoder.conversation.ConversationService;
import io.github.ddxpromax.webcoder.conversation.Conversation;
import io.github.ddxpromax.webcoder.conversation.MessageResponse;
import io.github.ddxpromax.webcoder.conversation.MessageService;
import io.github.ddxpromax.webcoder.conversation.MessagePageResponse;
import io.github.ddxpromax.webcoder.conversation.AssistantMessageStatus;
import io.github.ddxpromax.webcoder.conversation.MessageTurn;
import io.github.ddxpromax.webcoder.explanation.ExplainRequest;
import io.github.ddxpromax.webcoder.explanation.StreamingExplanationService;
import reactor.core.publisher.Flux;


@AutoConfigureMockMvc
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

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private MessageService messageService;

	@Test
	void messageHistoryLoadsRecentPageThenOlderPage() {
		Conversation conversation = conversationService.create();
		OffsetDateTime now = OffsetDateTime.now();

		insertMessage(
			conversation.id(), "user", "old", "python", now.minusSeconds(4));
		insertMessage(
			conversation.id(), "assistant", "middle", null, now.minusSeconds(2));
		insertMessage(
			conversation.id(), "user", "latest", "python", now);

		MessagePageResponse recentPage = messageService.findRecentByConversationId(
			conversation.id(), 2, null, null);

		assertThat(recentPage.conversationId()).isEqualTo(conversation.id());
		assertThat(recentPage.messages())
			.extracting(MessageResponse::content)
			.containsExactly("middle", "latest");
		assertThat(recentPage.messages())
			.extracting(MessageResponse::conversationId)
			.containsOnly(conversation.id());
		assertThat(recentPage.nextCursor()).isNotNull();

		MessagePageResponse olderPage =
			messageService.findRecentByConversationId(
				conversation.id(),
				2,
				recentPage.nextCursor().createdAt(),
				recentPage.nextCursor().id());

		assertThat(olderPage.messages())
				.extracting(MessageResponse::content)
				.containsExactly("old");
		assertThat(olderPage.nextCursor()).isNull();
	}

	private void insertMessage(
		UUID conversationId,
		String role,
		String content,
		String language,
		OffsetDateTime createdAt) {
			jdbcClient.sql("""
					insert into messages (conversation_id, role, content, language, status, created_at)
					values (:conversationId, :role, :content, :language, 'completed', :createdAt)
					""")
					.param("conversationId", conversationId)
					.param("role", role)
					.param("content", content)
					.param("language", language)
					.param("createdAt", createdAt)
					.update();
	}

	@Test
	void beginTurnPersistsUserMessageAndPendingAssistantMessage() {
		Conversation conversation = conversationService.create();

		MessageTurn turn = messageService.beginTurn(
			conversation.id(),
			"print('hello')",
			"python");

		assertThat(turn.userMessage().conversationId())
			.isEqualTo(conversation.id());
		assertThat(turn.userMessage().role()).isEqualTo("user");
		assertThat(turn.userMessage().content()).isEqualTo("print('hello')");
		assertThat(turn.userMessage().language()).isEqualTo("python");
		assertThat(turn.userMessage().status()).isEqualTo("completed");
		assertThat(turn.assistantMessage().conversationId()).isEqualTo(conversation.id());
		assertThat(turn.assistantMessage().content()).isEmpty();
		assertThat(turn.assistantMessage().role()).isEqualTo("assistant");
		assertThat(turn.assistantMessage().status()).isEqualTo("pending");

		boolean completed = messageService.finishAssistantTurn(
			conversation.id(),
			turn.assistantMessage().id(),
			"This prints hello.",
			AssistantMessageStatus.COMPLETED);

		boolean completedAgain = messageService.finishAssistantTurn(
			conversation.id(),
			turn.assistantMessage().id(),
			"This must not replace the first result.",
			AssistantMessageStatus.FAILED);

		assertThat(completed).isTrue();
		assertThat(completedAgain).isFalse();

		MessagePageResponse history =
			messageService.findRecentByConversationId(
				conversation.id(),
				10,
				null,
				null);

		assertThat(history.messages())
				.extracting(MessageResponse::role)
				.containsExactly("user", "assistant");
		assertThat(history.messages())
				.extracting(MessageResponse::content)
				.containsExactly("print('hello')", "This prints hello.");
		assertThat(history.messages())
				.extracting(MessageResponse::status)
				.containsExactly("completed", "completed");
	}

	@Test
	void failedAndCancelledTurnsKeepTheirPartialAssistantContent() {
		Conversation conversation = conversationService.create();

		MessageTurn failedTurn = messageService.beginTurn(
			conversation.id(),
			"explain this failing example",
			"java");

		boolean markedFailed = messageService.finishAssistantTurn(
			conversation.id(),
			failedTurn.assistantMessage().id(),
			"The explanation started but the AI service failed.",
			AssistantMessageStatus.FAILED);

		MessageTurn cancelledTurn = messageService.beginTurn(
			conversation.id(),
			"explain this cancelled example",
			"java");

		boolean markedCancelled = messageService.finishAssistantTurn(
			conversation.id(),
			cancelledTurn.assistantMessage().id(),
			"The explanation started before the client disconnected.",
			AssistantMessageStatus.CANCELLED);

		assertThat(markedFailed).isTrue();
		assertThat(markedCancelled).isTrue();

		MessagePageResponse history =
			messageService.findRecentByConversationId(
				conversation.id(),
				10,
				null,
				null);

		assertThat(history.messages())
				.extracting(MessageResponse::role)
				.containsExactly("user", "assistant", "user", "assistant");
		assertThat(history.messages())
				.extracting(MessageResponse::status)
				.containsExactly("completed", "failed", "completed", "cancelled");
		assertThat(history.messages())
				.extracting(MessageResponse::content)
				.containsExactly(
					"explain this failing example",
					"The explanation started but the AI service failed.",
					"explain this cancelled example",
					"The explanation started before the client disconnected.");
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StreamingExplanationService streamingExplanationService;

	@Test
	void messageStreamRouteReturnsSseAndPersistsCompletedMessages() throws Exception {
		Conversation conversation = conversationService.create();

		ServerSentEvent<String> token = ServerSentEvent.<String>builder(
			"{\"text\":\"Hello\"}")
			.event("token")
			.build();
		ServerSentEvent<String> done = ServerSentEvent.<String>builder(
			"[DONE]")
			.event("done")
			.build();

		when(streamingExplanationService.stream(any(ExplainRequest.class)))
			.thenReturn(Flux.just(token, done));

		MvcResult initialResult = mockMvc.perform(
			post("/api/conversations/{conversationId}/messages/stream",
				conversation.id())
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.content("""
						{
							"code": "print('hello')",
							"language": "python"
						}
						"""))
				.andExpect(request().asyncStarted())
				.andReturn();

		mockMvc.perform(asyncDispatch(initialResult))
						.andExpect(status().isOk())
						.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
						.andExpect(content().string(containsString("event:token")))
						.andExpect(content().string(containsString("{\"text\":\"Hello\"}")))
						.andExpect(content().string(containsString("event:done")))
						.andExpect(content().string(containsString("[DONE]")));

		MessagePageResponse history = messageService.findRecentByConversationId(
			conversation.id(),
			10,
			null,
			null);

		assertThat(history.messages())
			.extracting(MessageResponse::role)
			.containsExactly("user", "assistant");
		assertThat(history.messages())
			.extracting(MessageResponse::status)
			.containsExactly("completed", "completed");
		assertThat(history.messages())
			.extracting(MessageResponse::content)
			.containsExactly("print('hello')", "Hello");
	}

	@Test
	void messageStreamRouteReturnsNotFoundForUnknownConversation() throws Exception {
		UUID unknownConversationId = UUID.randomUUID();

		mockMvc.perform(
			post("/api/conversations/{conversationId}/messages/stream",
				unknownConversationId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.content("""
						{
							"code": "print('hello')",
							"language": "python"
						}
						"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void messageStreamRouteRejectsBlankCode() throws Exception {
		Conversation conversation = conversationService.create();

		mockMvc.perform(
			post("/api/conversations/{conversationId}/messages/stream",
				conversation.id())
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.content("""
						{
							"code": " ",
							"language": "python"
						}
						"""))
				.andExpect(status().isBadRequest());

		MessagePageResponse history = messageService.findRecentByConversationId(
			conversation.id(),
			10,
			null,
			null);

		assertThat(history.messages()).isEmpty();
	}
}
