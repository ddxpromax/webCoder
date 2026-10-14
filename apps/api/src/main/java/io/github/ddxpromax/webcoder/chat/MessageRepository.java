package io.github.ddxpromax.webcoder.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByChatIdOrderByCreatedAtAsc(String chatId);
    long countByChatId(String chatId);
    void deleteByChatId(String chatId);
}