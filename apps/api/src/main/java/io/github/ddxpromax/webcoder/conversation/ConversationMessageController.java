package io.github.ddxpromax.webcoder.conversation;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ddxpromax.webcoder.explanation.ExplainRequest;

import reactor.core.publisher.Flux;


@RestController
@RequestMapping("/api/conversations")
public class ConversationMessageController {

    private final MessageService messageService;
    private final ConversationMessageStreamService streamService;

    public ConversationMessageController(
        MessageService messageService,
        ConversationMessageStreamService streamService) {
            this.messageService = messageService;
            this.streamService = streamService;
    }

    @PostMapping(
        path = "/{conversationId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamMessage(
        @PathVariable UUID conversationId,
        @Valid @RequestBody ExplainRequest request) {
            MessageTurn turn = messageService.beginTurn(
                conversationId,
                request.code(),
                request.language());

            return streamService.stream(
                conversationId,
                turn.assistantMessage().id(),
                request);
    }
}
