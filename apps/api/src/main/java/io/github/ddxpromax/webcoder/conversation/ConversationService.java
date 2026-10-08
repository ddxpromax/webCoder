package io.github.ddxpromax.webcoder.conversation;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "New conversation";

    private final ConversationRepository conversationRepository;

    public ConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Transactional
    public Conversation create() {
        return conversationRepository.create(DEFAULT_TITLE);
    }

    @Transactional(readOnly=true)
    public List<Conversation> findRecent(int limit) {
        return conversationRepository.findRecent(limit);
    }

    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        return conversationRepository.existsById(id);
    }
}
