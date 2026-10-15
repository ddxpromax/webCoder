package io.github.ddxpromax.webcoder.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatsController {
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatsController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/chats")
    public List<ChatSpecDto> list(
        @RequestParam(name = "user_id", required = false) String userId,
        @RequestParam(required = false) String channel,
        @RequestParam(required = false) Boolean archived,
        @RequestHeader(name = "X-Agent-Id", required = false) String agentId
    ) {
        List<ChatEntity> chats = agentId != null && !agentId.isBlank()
            ? chatService.listByAgent(agentId, archived)
            : chatService.list(userId, channel, archived);

        return chats.stream().map(ChatSpecDto::from).toList();
    }

    @PostMapping("/chats")
    public ChatSpecDto create() {
        return ChatSpecDto.from(chatService.create());
    }

    @GetMapping("/chats/{id}")
    public ResponseEntity<?> getChat(@PathVariable String id) {
        ChatEntity chat = chatService.getChat(id);
        if (chat == null) {
            return ResponseEntity.status(404)
                .body(Map.of("detail", "Chat not found: " + id));
        }

        List<Map<String, Object>> messages = chatService.getMessages(id).stream()
            .map(this::toHistoryMessage)
            .toList();

        return ResponseEntity.ok(new ChatHistoryDto(messages, chat.getStatus()));
    }

    @PostMapping("/chats/{id}/messages")
    public Map<String, String> saveMessages(
        @PathVariable String id,
        @RequestBody List<Map<String, String>> messages
    ) {
        chatService.saveMessages(id, messages);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/chats/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        chatService.delete(id);
        return Map.of("deleted", true);
    }

    @PatchMapping("/chats/{id}")
    public Object rename(
        @PathVariable String id,
        @RequestBody Map<String, String> body
    ) {
        String name = body.getOrDefault("name", body.get("title"));
        ChatEntity chat = chatService.rename(
            id,
            name == null ? "New Chat" : name
        );
        return chat == null
            ? Map.of("error", "not found")
            : ChatSpecDto.from(chat);
    }

    @PutMapping("/chats/{id}")
    @Transactional
    public Object update(
        @PathVariable String id,
        @RequestBody Map<String, String> body
    ) {
        String name = body.getOrDefault("name", body.get("title"));
        if (name != null && !name.isBlank()) {
            chatService.rename(id, name);
        }
        if (body.containsKey("pinned")) {
            ChatEntity chat = chatService.getChat(id);
            if (chat != null) {
                chat.setPinned(Boolean.valueOf(body.get("pinned")));
            }
        }

        ChatEntity chat = chatService.getChat(id);
        return chat == null
            ? Map.of("error", "not found")
            : ChatSpecDto.from(chat);
    }

    @PostMapping("/chats/{id}/archive")
    @Transactional
    public ChatSpecDto archive(@PathVariable String id) {
        chatService.setArchived(id, true);
        return ChatSpecDto.from(chatService.getChat(id));
    }

    @PostMapping("/chats/{id}/unarchive")
    @Transactional
    public ChatSpecDto unarchive(@PathVariable String id) {
        chatService.setArchived(id, false);
        return ChatSpecDto.from(chatService.getChat(id));
    }

    @PostMapping("/chats/batch-delete")
    @Transactional
    public Map<String, String> batchDelete(@RequestBody List<String> ids) {
        if (ids != null) {
            ids.forEach(chatService::delete);
        }
        return Map.of("status", "ok");
    }

    @PostMapping("/chats/actions/batch-archive")
    @Transactional
    public Map<String, String> batchArchive(
        @RequestBody Map<String, Object> body
    ) {
        for (String id : chatIds(body)) {
            chatService.setArchived(id, true);
        }
        return Map.of("status", "ok");
    }

    @PostMapping("/chats/actions/batch-unarchive")
    @Transactional
    public Map<String, String> batchUnarchive(
        @RequestBody Map<String, Object> body
    ) {
        for (String id : chatIds(body)) {
            chatService.setArchived(id, false);
        }
        return Map.of("status", "ok");
    }

    private static List<String> chatIds(Map<String, Object> body) {
        Object raw = body.get("chat_ids");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
    }

    private Map<String, Object> toHistoryMessage(MessageEntity message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId().toString());
        result.put("type",
            message.getThinking() == null || message.getThinking().isBlank()
                ? "message" : "reasoning");
        result.put("role", message.getRole());
        result.put("content", parseContent(message.getContent()));
        result.put("status", "completed");
        result.put("created_at",
            message.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        result.put("metadata",
            message.getToolCalls() == null
                ? Map.of()
                : Map.of("toolCalls", message.getToolCalls()));
        return result;
    }

    private Object parseContent(String content) {
        if (content == null || !content.stripLeading().startsWith("[")) {
            return content;
        }
        try {
            return objectMapper.readValue(content, Object.class);
        } catch (Exception ignored) {
            return content;
        }
    }
}