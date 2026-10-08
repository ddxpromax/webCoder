package io.github.ddxpromax.webcoder.conversation;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import io.github.ddxpromax.webcoder.explanation.ExplainRequest;
import io.github.ddxpromax.webcoder.explanation.StreamingExplanationService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


@Service
public class ConversationMessageStreamService {

    private final MessageService messageService;
    private final StreamingExplanationService streamingExplanationService;
    private final ObjectMapper objectMapper;

    public ConversationMessageStreamService(
        MessageService messageService,
        StreamingExplanationService streamingExplanationService,
        ObjectMapper objectMapper) {
            this.messageService = messageService;
            this.streamingExplanationService = streamingExplanationService;
            this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> stream(
        UUID conversationId,
        long assistantMessageId,
        ExplainRequest request) {
            StreamState state = new StreamState();

            return Flux.usingWhen(
                Mono.just(state),
                currentState -> streamingExplanationService.stream(request)
                    .concatMap(event -> handleEvent(
                        currentState,
                        conversationId,
                        assistantMessageId,
                        event))
                    .takeUntil(ConversationMessageStreamService::isTerminalEvent)
                    .onErrorResume(exception -> {
                        currentState.markTerminalIfAbsent(
                            AssistantMessageStatus.FAILED);
                        return persistTerminalState(conversationId,
                            assistantMessageId,
                            currentState,
                            AssistantMessageStatus.FAILED)
                            .thenMany(Flux.just(errorEvent(
                                "The AI service could not complete the request.")));
                    }),
                    currentState -> persistTerminalState(
                        conversationId,
                        assistantMessageId,
                        currentState,
                        AssistantMessageStatus.FAILED),
                    (currentState, exception) -> persistTerminalState(
                        conversationId,
                        assistantMessageId,
                        currentState,
                        AssistantMessageStatus.FAILED),
                    currentState -> persistTerminalState(
                        conversationId,
                        assistantMessageId,
                        currentState,
                        AssistantMessageStatus.CANCELLED));
    }

    private Mono<ServerSentEvent<String>> handleEvent(
        StreamState state,
        UUID conversationId,
        long assistantMessageId,
        ServerSentEvent<String> event) {
            if ("token".equals(event.event())) {
                return Mono.fromCallable(() -> {
                    JsonNode data = objectMapper.readTree(event.data());
                    JsonNode text = data == null ? null : data.get("text");

                    if (text == null || !text.isString()) {
                        throw new IllegalArgumentException(
                            "The token event does not contain text.");
                    }

                    state.append(text.asString());
                    return event;
                }).onErrorResume(exception -> {
                    state.markTerminalIfAbsent(
                        AssistantMessageStatus.FAILED);
                    return persistTerminalState(
                        conversationId,
                        assistantMessageId,
                        state,
                        AssistantMessageStatus.FAILED)
                        .thenReturn(errorEvent(
                            "The AI service returned an invalid token event."));
                });
            }

            if ("done".equals(event.event())) {
                if ("[DONE]".equals(event.data())) {
                    state.markTerminalIfAbsent(
                        AssistantMessageStatus.COMPLETED);
                    return persistTerminalState(
                        conversationId,
                        assistantMessageId,
                        state,
                        AssistantMessageStatus.FAILED)
                        .thenReturn(event);
                }

                state.markTerminalIfAbsent(
                    AssistantMessageStatus.FAILED);
                return persistTerminalState(
                    conversationId,
                    assistantMessageId,
                    state,
                    AssistantMessageStatus.FAILED)
                    .thenReturn(errorEvent(
                        "The AI service returned an invalid completion event."));
            }

            if ("error".equals(event.event())) {
                state.markTerminalIfAbsent(
                    AssistantMessageStatus.FAILED);
                return persistTerminalState(
                    conversationId,
                    assistantMessageId,
                    state,
                    AssistantMessageStatus.FAILED)
                    .thenReturn(event);
            }

            return Mono.just(event);
    }

    private Mono<Void> persistTerminalState(
        UUID conversationId,
        long assistantMessageId,
        StreamState state,
        AssistantMessageStatus fallbackStatus) {
            AssistantMessageStatus status = state.terminalStatusOr(fallbackStatus);
        String content = state.content();

        return Mono.fromRunnable(() -> messageService.finishAssistantTurn(
            conversationId,
            assistantMessageId,
            content,
            status))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private static boolean isTerminalEvent(
        ServerSentEvent<String> event) {
            return "done".equals(event.event())
                || "error".equals(event.event());
    }

    private static ServerSentEvent<String> errorEvent(String message) {
        return ServerSentEvent.<String>builder(message)
            .event("error")
            .build();
    }

    private static final class StreamState {
        private final StringBuilder content = new StringBuilder();
        private AssistantMessageStatus terminalStatus;

        synchronized void append(String token) {
            if (terminalStatus == null) {
                content.append(token);
            }
        }

        synchronized void markTerminalIfAbsent(
            AssistantMessageStatus status) {
                if (terminalStatus == null) {
                    terminalStatus = status;
                }
        }

        synchronized AssistantMessageStatus terminalStatusOr(
            AssistantMessageStatus fallbackStatus) {
                if (terminalStatus == null) {
                    terminalStatus = fallbackStatus;
                }
                return terminalStatus;
        }

        synchronized String content() {
            return content.toString();
        }
    }
}
