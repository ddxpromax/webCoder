package io.github.ddxpromax.webcoder.conversation;

import java.net.URI;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;


@RestController
@RequestMapping("/api/conversations")
@Validated
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
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
}
