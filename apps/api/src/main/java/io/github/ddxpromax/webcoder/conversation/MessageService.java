package io.github.ddxpromax.webcoder.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


@Service
public class MessageService {

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;

    public MessageService(
        ConversationService conversationService,
        MessageRepository messageRepository) {
            this.conversationService = conversationService;
            this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public MessagePageResponse findRecentByConversationId(
        UUID conversationId,
        int limit,
        Instant beforeCreatedAt,
        Long beforeId) {
            if ((beforeCreatedAt == null) != (beforeId == null)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "beforeCreatedAt and beforeId must be provided together.");
            }

            if (!conversationService.existsById(conversationId)) {
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Conversation not found.");
            }

            List<Message> fetched = messageRepository.findRecentPage(
                conversationId,
                limit + 1,
                beforeCreatedAt,
                beforeId);

            boolean hasMore = fetched.size() > limit;
            List<Message> page = new ArrayList<>(
                fetched.subList(0, Math.min(limit, fetched.size())));

            MessageCursor nextCursor = null;
            if (hasMore) {
                Message oldestInPage = page.get(page.size() - 1);
                nextCursor = new MessageCursor(
                    oldestInPage.createdAt(),
                    oldestInPage.id());
            }

            Collections.reverse(page);

            return new MessagePageResponse(
                conversationId,
                page.stream().map(MessageResponse::from).toList(),
                nextCursor);
    }

    @Transactional
    public MessageTurn beginTurn(
        UUID conversationId,
        String content,
        String language) {
            if (!conversationService.existsById(conversationId)) {
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Conversation not found.");
            }

            Message userMessage = messageRepository.insert(
                conversationId,
                "user",
                content,
                language,
                "completed");

            Message assistantMessage = messageRepository.insert(
                conversationId,
                "assistant",
                "",
                language,
                "pending");

            int updatedConversations = messageRepository.touchConversation(conversationId);
            if (updatedConversations != 1) {
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Conversation not found.");
            }

            return new MessageTurn(
                MessageResponse.from(userMessage),
                MessageResponse.from(assistantMessage));
    }

    @Transactional
    public boolean finishAssistantTurn(
        UUID conversationId,
        long assistantMessageId,
        String content,
        AssistantMessageStatus status) {
            int updatedMessages =
                messageRepository.transitionPendingAssistantMessage(
                    assistantMessageId,
                    content,
                    status.databaseValue());

            if (updatedMessages == 0) {
                return false;
            }

            int updatedConversations = messageRepository.touchConversation(conversationId);

            if (updatedConversations != 1) {
                throw new IllegalStateException(
                    "Conversation could not be updated.");
            }

            return true;
    }
}
