package io.github.ddxpromax.webcoder.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Persists Majo's pending auto-memory turns and flushes them by interval. */
@Component
public class MemoryWritePipeline {
    private static final Logger log = LoggerFactory.getLogger(
        MemoryWritePipeline.class
    );
    private static final String STATE_FILE =
        ".auto_memory_state.json";
    private static final int MAX_TURN_CHARS = 4000;

    private final AgentStore agents;
    private final MemoryBackendRegistry backends;
    private final JsonFileStore files;
    private final ConcurrentHashMap<String, AgentState> states =
        new ConcurrentHashMap<>();

    private static final class PendingTurn {
        String id;
        String user;
        String assistant;

        PendingTurn(String id, String user, String assistant) {
            this.id = id;
            this.user = user;
            this.assistant = assistant;
        }
    }

    private static final class AgentState {
        final List<PendingTurn> pending = new ArrayList<>();
    }

    public MemoryWritePipeline(
        AgentStore agents,
        MemoryBackendRegistry backends,
        JsonFileStore files
    ) {
        this.agents = agents;
        this.backends = backends;
        this.files = files;
    }

    public String beginTurn(String agentId, String userText) {
        if (userText == null || userText.isBlank()
            || userText.strip().startsWith("/")) {
            return null;
        }
        AgentState state = state(agentId);
        synchronized (state) {
            String id = UUID.randomUUID().toString();
            state.pending.add(new PendingTurn(
                id, truncate(userText), ""
            ));
            persist(agentId, state);
            return id;
        }
    }

    public void finishTurn(
        String agentId,
        String turnId,
        String assistantText
    ) {
        if (turnId == null) {
            return;
        }
        AgentState state = state(agentId);
        synchronized (state) {
            for (PendingTurn turn : state.pending) {
                if (turnId.equals(turn.id)) {
                    turn.assistant = truncate(assistantText);
                    break;
                }
            }
            persist(agentId, state);
        }
        maybeFlush(agentId);
    }

    public String memoryPrompt(String agentId) {
        MemoryBackend backend = backends.resolve(agentId);
        return backend == null ? "" : backend.getMemoryPrompt();
    }

    private void maybeFlush(String agentId) {
        Map<String, Object> config = autoConfig(agentId);
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return;
        }
        int interval = config.get("interval") instanceof Number number
            && number.intValue() > 0
            ? number.intValue() : 1;
        MemoryBackend backend = backends.resolve(agentId);
        if (backend == null) {
            log.warn("Automatic memory is enabled but unavailable for agent '{}'",
                agentId);
            return;
        }
        AgentState state = state(agentId);
        synchronized (state) {
            List<PendingTurn> completed = state.pending.stream()
                .filter(turn -> !turn.assistant.isBlank())
                .toList();
            if (completed.size() < interval) {
                return;
            }
            List<PendingTurn> batch = new ArrayList<>(completed);
            state.pending.removeAll(batch);
            persist(agentId, state);
            try {
                backend.remember(renderBatch(batch), Map.of(
                    "agent_id", agentId,
                    "trigger", "periodic",
                    "turns", batch.size(),
                    "backend", backend.id()
                ));
            } catch (Exception exception) {
                state.pending.addAll(0, batch);
                persist(agentId, state);
                log.warn("Automatic memory write failed for agent '{}'",
                    agentId, exception);
            }
        }
    }

    private Map<String, Object> autoConfig(String agentId) {
        Object value = backends.memoryConfig(agentId)
            .get("auto_memory_config");
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(
            String.valueOf(key), item
        ));
        return result;
    }

    private AgentState state(String agentId) {
        return states.computeIfAbsent(agentId, this::load);
    }

    private AgentState load(String agentId) {
        AgentState state = new AgentState();
        Map<String, Object> stored = files.read(stateFile(agentId),
            Map.of());
        Object raw = stored.get("pending");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry
                    && entry.get("id") != null) {
                    state.pending.add(new PendingTurn(
                        String.valueOf(entry.get("id")),
                        String.valueOf(entry.get("user") == null
                            ? "" : entry.get("user")),
                        String.valueOf(entry.get("assistant") == null
                            ? "" : entry.get("assistant"))
                    ));
                }
            }
        }
        return state;
    }

    private void persist(String agentId, AgentState state) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (PendingTurn turn : state.pending) {
            pending.add(Map.of(
                "id", turn.id,
                "user", turn.user,
                "assistant", turn.assistant
            ));
        }
        files.writeAtomic(stateFile(agentId), Map.of(
            "pending", pending
        ));
    }

    private Path stateFile(String agentId) {
        return agents.workspaceDirForAgent(agentId)
            .resolve("memory").resolve(STATE_FILE);
    }

    private static String renderBatch(List<PendingTurn> turns) {
        StringBuilder content = new StringBuilder(
            "Conversation memory excerpt (last "
        ).append(turns.size()).append(" turns):\n");
        for (PendingTurn turn : turns) {
            content.append("\nUser: ").append(turn.user)
                .append("\nAssistant: ")
                .append(turn.assistant).append('\n');
        }
        return content.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() > MAX_TURN_CHARS
            ? stripped.substring(0, MAX_TURN_CHARS) + "..."
            : stripped;
    }
}
