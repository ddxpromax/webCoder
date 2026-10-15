package io.github.ddxpromax.webcoder.agent;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ActiveTurnRegistry {
    private static final Logger log =
        LoggerFactory.getLogger(ActiveTurnRegistry.class);

    private final ConcurrentHashMap<String, Turn> turns =
        new ConcurrentHashMap<>();

    public void register(String chatId, HarnessAgent agent) {
        if (chatId != null && !chatId.isBlank() && agent != null) {
            if (turns.putIfAbsent(chatId, new Turn(agent)) != null) {
                throw new IllegalStateException(
                    "Chat already has an active turn: " + chatId
                );
            }
        }
    }

    public void unregister(String chatId) {
        if (chatId == null) {
            return;
        }
        Turn turn = turns.remove(chatId);
        if (turn != null) {
            turn.finished().complete(null);
        }
    }

    public boolean isActive(String chatId) {
        return chatId != null && turns.containsKey(chatId);
    }

    public Set<String> activeChatIds() {
        return Set.copyOf(turns.keySet());
    }

    /** Interrupt the agent and wait for its persistence cleanup. */
    public boolean stop(String chatId) {
        Turn turn = chatId == null ? null : turns.get(chatId);
        if (turn == null) {
            return false;
        }
        try {
            turn.agent().interrupt();
            turn.finished().get(5, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            log.warn("Failed to stop turn for chat {}", chatId,
                exception);
            return false;
        }
    }

    private record Turn(
        HarnessAgent agent,
        CompletableFuture<Void> finished
    ) {
        private Turn(HarnessAgent agent) {
            this(agent, new CompletableFuture<>());
        }
    }
}
