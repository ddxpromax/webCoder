package io.github.ddxpromax.webcoder.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import io.github.ddxpromax.webcoder.explanation.ExplainRequest;
import io.github.ddxpromax.webcoder.explanation.StreamingExplanationService;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;


public class ConversationMessageStreamServiceTests {

    private MessageService messageService;
    private StreamingExplanationService streamingExplanationService;
    private ConversationMessageStreamService streamService;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        streamingExplanationService = mock(StreamingExplanationService.class);

        streamService = new ConversationMessageStreamService(
            messageService,
            streamingExplanationService,
            JsonMapper.builder().build());
    }

    @Test
    void persistsCompletedAssistantMessageBeforeSendingDoneEvent() {
        UUID conversationId = UUID.fromString("5de1fc26-6d10-4bc2-83c8-29e15b07cf54");
        long assistantMessageId = 42L;
        ExplainRequest request = new ExplainRequest("print('hello')", "python");
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicReference<String> savedContent = new AtomicReference<>();
        AtomicReference<AssistantMessageStatus> savedStatus = new AtomicReference<>();

        ServerSentEvent<String> token = ServerSentEvent.<String>builder(
            "{\"text\":\"Hello\"}")
                .event("token")
                .build();
        ServerSentEvent<String> done = ServerSentEvent.<String>builder(
            "[DONE]")
                .event("done")
                .build();

        when(streamingExplanationService.stream(request))
            .thenReturn(Flux.just(token, done));
        when(messageService.finishAssistantTurn(
            eq(conversationId),
            eq(assistantMessageId),
            any(String.class),
            any(AssistantMessageStatus.class)))
            .thenAnswer(invocation -> {
                savedContent.set(invocation.getArgument(2));
                savedStatus.set(invocation.getArgument(3));
                persisted.set(true);
                return true;
            });

        StepVerifier.create(streamService.stream(
            conversationId,
            assistantMessageId,
            request))
            .expectNextMatches(event ->
                "token".equals(event.event())
                    && "{\"text\":\"Hello\"}".equals(event.data()))
            .expectNextMatches(event ->
                "done".equals(event.event())
                    && "[DONE]".equals(event.data())
                    && persisted.get())
            .verifyComplete();

        assertThat(savedContent.get()).isEqualTo("Hello");
        assertThat(savedStatus.get()).isEqualTo(AssistantMessageStatus.COMPLETED);

        verify(messageService, atLeastOnce()).finishAssistantTurn(
            eq(conversationId),
            eq(assistantMessageId),
            eq("Hello"),
            eq(AssistantMessageStatus.COMPLETED));
    }

    @Test
    void keepsPartialContentAndMarksMessageFailedOnErrorEvent() {
        UUID conversationId = UUID.fromString("5de1fc26-6d10-4bc2-83c8-29e15b07cf54");
        long assistantMessageId = 43L;
        ExplainRequest request = new ExplainRequest("print('partial')", "python");
        AtomicReference<String> savedContent = new AtomicReference<>();
        AtomicReference<AssistantMessageStatus> savedStatus = new AtomicReference<>();

        ServerSentEvent<String> token = ServerSentEvent.<String>builder(
            "{\"text\":\"Partial reply\"}")
            .event("token")
            .build();
        ServerSentEvent<String> error = ServerSentEvent.<String>builder(
            "The AI service is temporarily unavailable.")
            .event("error")
            .build();

        when(streamingExplanationService.stream(request))
            .thenReturn(Flux.just(token, error));
        when(messageService.finishAssistantTurn(
            eq(conversationId),
            eq(assistantMessageId),
            any(String.class),
            any(AssistantMessageStatus.class)))
            .thenAnswer(invocation -> {
                savedContent.set(invocation.getArgument(2));
                savedStatus.set(invocation.getArgument(3));
                return true;
            });

        StepVerifier.create(streamService.stream(
            conversationId,
            assistantMessageId,
            request))
            .expectNextMatches(event -> "token".equals(event.event()))
            .expectNextMatches(event -> "error".equals(event.event())
                && "The AI service is temporarily unavailable.".equals(event.data()))
            .verifyComplete();

        assertThat(savedContent.get()).isEqualTo("Partial reply");
        assertThat(savedStatus.get()).isEqualTo(AssistantMessageStatus.FAILED);
        verify(messageService, atLeastOnce()).finishAssistantTurn(
            eq(conversationId),
            eq(assistantMessageId),
            eq("Partial reply"),
            eq(AssistantMessageStatus.FAILED));
    }

    @Test
    void marksMessageCancelledWhenClientCancelsSubscription() {
        UUID conversationId = UUID.fromString("5de1fc26-6d10-4bc2-83c8-29e15b07cf54");
        long assistantMessageId = 44L;
        ExplainRequest request = new ExplainRequest("print('partial')", "python");

        ServerSentEvent<String> token = ServerSentEvent.<String>builder(
            "{\"text\":\"Partial reply\"}")
            .event("token")
            .build();

        when(streamingExplanationService.stream(request))
            .thenReturn(Flux.concat(
                Flux.just(token),
                Flux.never()));
        when(messageService.finishAssistantTurn(
            eq(conversationId),
            eq(assistantMessageId),
            eq("Partial reply"),
            eq(AssistantMessageStatus.CANCELLED)))
            .thenReturn(true);

        StepVerifier.create(streamService.stream(
            conversationId,
            assistantMessageId,
            request))
            .expectNextMatches(event -> "token".equals(event.event()))
            .thenCancel()
            .verify();

        verify(messageService, org.mockito.Mockito.timeout(2_000))
            .finishAssistantTurn(
                eq(conversationId),
                eq(assistantMessageId),
                eq("Partial reply"),
                eq(AssistantMessageStatus.CANCELLED));
    }
}
