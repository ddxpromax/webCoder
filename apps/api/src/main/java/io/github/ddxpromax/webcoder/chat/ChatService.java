package io.github.ddxpromax.webcoder.chat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    public static final int CHAT_NAME_MAX_LENGTH = 255;

    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;

    public ChatService(
        ChatRepository chatRepository,
        MessageRepository messageRepository
    ) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
    }

    public List<ChatEntity> list() {
        return chatRepository.findAllByOrderByUpdatedAtDesc();
    }

    public List<ChatEntity> list(
        String userId,
        String channel,
        Boolean archived
    ) {
        if (Boolean.TRUE.equals(archived)) {
            return chatRepository.findAllByArchivedAtIsNotNullOrderByUpdatedAtDesc();
        }
        if (Boolean.FALSE.equals(archived)) {
            return chatRepository.findAllByArchivedAtIsNullOrderByUpdatedAtDesc();
        }
        return chatRepository.findAllByOrderByUpdatedAtDesc();
    }

    public List<ChatEntity> listByAgent(
        String agentId,
        Boolean archived
    ) {
        if (agentId == null || agentId.isBlank()) {
            return list(null, null, archived);
        }
        if (Boolean.TRUE.equals(archived)) {
            return chatRepository
                .findAllByAgentIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(
                    agentId
                );
        }
        if (Boolean.FALSE.equals(archived)) {
            return chatRepository
                .findAllByAgentIdAndArchivedAtIsNullOrderByUpdatedAtDesc(
                    agentId
                );
        }
        return chatRepository.findAllByAgentIdOrderByUpdatedAtDesc(agentId);
    }

    @Transactional
    public ChatEntity getOrCreateBySession(
        String agentId,
        String sessionId,
        String firstPrompt
    ) {
        var existing = agentId != null && !agentId.isBlank()
            ? chatRepository.findByAgentIdAndSessionId(agentId, sessionId)
            : chatRepository.findBySessionId(sessionId);

        if (existing.isPresent()) {
            ChatEntity chat = existing.get();
            chat.setUpdatedAt(now());
            return chatRepository.save(chat);
        }

        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID().toString());
        chat.setSessionId(sessionId);
        chat.setTitle(firstPrompt == null || firstPrompt.isBlank()
            ? "New Chat"
            : firstPrompt.substring(0, Math.min(10, firstPrompt.length())));
        chat.setStatus("idle");
        if (agentId != null && !agentId.isBlank()) {
            chat.setAgentId(agentId);
        }
        chat.setCreatedAt(now());
        chat.setUpdatedAt(now());
        return chatRepository.save(chat);
    }

    @Transactional
    public boolean patchChatIfNameMatches(
        String chatId,
        String expectedTitle,
        String newTitle
    ) {
        ChatEntity chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null || !Objects.equals(chat.getTitle(), expectedTitle)) {
            return false;
        }
        chat.setTitle(boundName(newTitle));
        chat.setUpdatedAt(now());
        chatRepository.save(chat);
        return true;
    }

    @Transactional
    public ChatEntity create() {
        ChatEntity chat = new ChatEntity();
        chat.setId(UUID.randomUUID().toString());
        chat.setTitle("New Chat");
        chat.setStatus("idle");
        chat.setCreatedAt(now());
        chat.setUpdatedAt(now());
        return chatRepository.save(chat);
    }

    @Transactional
    public ChatEntity create(ChatEntity chat) {
        if (chat.getCreatedAt() == null) {
            chat.setCreatedAt(now());
        }
        if (chat.getUpdatedAt() == null) {
            chat.setUpdatedAt(now());
        }
        return chatRepository.save(chat);
    }

    public List<MessageEntity> getMessages(String chatId) {
        return messageRepository.findByChatIdOrderByCreatedAtAsc(chatId);
    }

    @Transactional
    public ChatEntity getChat(String chatId) {
        return chatRepository.findById(chatId).orElse(null);
    }

    @Transactional
    public void setStatus(String chatId, String status) {
        chatRepository.findById(chatId).ifPresent(chat -> {
            chat.setStatus(status);
            chat.setUpdatedAt(now());
            chatRepository.save(chat);
        });
    }

    @Transactional
    public ChatEntity setProjectDir(String chatId, String projectDir) {
        return chatRepository.findById(chatId).map(chat -> {
            chat.setProjectDir(projectDir);
            chat.setUpdatedAt(now());
            return chatRepository.save(chat);
        }).orElse(null);
    }

    @Transactional
    public void saveMessages(
        String chatId,
        List<Map<String, String>> messages
    ) {
        if (messages.isEmpty()) {
            return;
        }

        chatRepository.findById(chatId).ifPresent(chat -> {
            chat.setUpdatedAt(now());
            chat.setStatus("idle");
            chatRepository.save(chat);
        });

        for (Map<String, String> message : messages) {
            MessageEntity entity = new MessageEntity();
            entity.setChatId(chatId);
            entity.setRole(message.get("role"));
            entity.setContent(message.getOrDefault("content", ""));
            entity.setToolCalls(message.get("toolCalls"));
            entity.setThinking(message.get("thinking"));
            entity.setCreatedAt(now());
            messageRepository.save(entity);
        }
    }

    @Transactional
    public void delete(String chatId) {
        chatRepository.deleteById(chatId);
    }

    @Transactional
    public ChatEntity rename(String chatId, String title) {
        ChatEntity chat = chatRepository.findById(chatId).orElse(null);
        if (chat != null) {
            chat.setTitle(boundName(title));
            chat.setUpdatedAt(now());
            chatRepository.save(chat);
        }
        return chat;
    }

    @Transactional
    public void setArchived(String chatId, boolean archived) {
        chatRepository.findById(chatId).ifPresent(chat -> {
            chat.setArchivedAt(archived ? now() : null);
            chat.setUpdatedAt(now());
            chatRepository.save(chat);
        });
    }

    private static String boundName(String title) {
        if (title == null) {
            return "New Chat";
        }
        return title.length() > CHAT_NAME_MAX_LENGTH
            ? title.substring(0, CHAT_NAME_MAX_LENGTH)
            : title;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}