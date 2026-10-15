package io.github.ddxpromax.webcoder.chat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<ChatEntity, String> {
    List<ChatEntity> findAllByOrderByUpdatedAtDesc();
    List<ChatEntity> findAllByArchivedAtIsNullOrderByUpdatedAtDesc();
    List<ChatEntity> findAllByArchivedAtIsNotNullOrderByUpdatedAtDesc();

    List<ChatEntity> findAllByAgentIdOrderByUpdatedAtDesc(String agentId);
    List<ChatEntity> findAllByAgentIdAndArchivedAtIsNullOrderByUpdatedAtDesc(
        String agentId
    );
    List<ChatEntity> findAllByAgentIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(
        String agentId
    );

    Optional<ChatEntity> findBySessionId(String sessionId);
    Optional<ChatEntity> findByAgentIdAndSessionId(
        String agentId,
        String sessionId
    );
}