package io.github.ddxpromax.webcoder.conversation;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.format.annotation.DateTimeFormat;


@RestController
@RequestMapping("/api/conversations")
@Validated
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;

    public ConversationController(
        ConversationService conversationService,
        MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService  = messageService;
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> create() {
        Conversation conversation = conversationService.create();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(conversation.id())
            .toUri();

        return ResponseEntity.created(location)
            .body(ConversationResponse.from(conversation));
    }

    @GetMapping
    public List<ConversationResponse> findRecent(
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(100)
        int limit) {
            return conversationService.findRecent(limit).stream()
                .map(ConversationResponse::from)
                .toList();
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePageResponse findMessages(
        @PathVariable UUID conversationId,
        @RequestParam(defaultValue = "50")
        @Min(1)
        @Max(100)
        int limit,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant beforeCreatedAt,
        @RequestParam(required = false)
        @Positive
        Long beforeId) {
            return messageService.findRecentByConversationId(
                conversationId,
                limit,
                beforeCreatedAt,
                beforeId);
    }
}
