package io.github.ddxpromax.webcoder.loop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Session-scoped state for Majo goal, mission, and custom loop modes. */
@Component
public class LoopSessionManager {
    public static final class Session {
        public final String sessionId;
        public final String modeId;
        public final String modeName;
        public final String goal;
        public final int maxIterations;
        public final long maxTokens;
        public final long startedNanos = System.nanoTime();
        public volatile int iteration;
        public volatile int totalTokens;
        public volatile String state = "running";
        public volatile String lastResult = "";

        Session(String sessionId, String modeId, String modeName, String goal, int maxIterations, long maxTokens) {
            this.sessionId = sessionId; this.modeId = modeId; this.modeName = modeName; this.goal = goal;
            this.maxIterations = Math.max(1, maxIterations); this.maxTokens = Math.max(0, maxTokens);
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    public Session start(String sessionId, String modeId, String modeName, String goal, int maxIterations, long maxTokens) {
        Session next = new Session(sessionId, modeId, modeName, goal, maxIterations, maxTokens);
        sessions.put(sessionId, next); return next;
    }
    public Session get(String sessionId) { return sessionId == null ? null : sessions.get(sessionId); }
    public void end(String sessionId) { Session session = sessions.remove(sessionId); if (session != null) session.state = "idle"; }
    public boolean active(String sessionId) { Session session = get(sessionId); return session != null && "running".equals(session.state); }
    public Map<String, Object> status(String sessionId) {
        Session session = get(sessionId);
        if (session == null || "idle".equals(session.state)) {
            Map<String, Object> idle = new LinkedHashMap<>(); idle.put("state", "idle"); idle.put("mode", null); return idle;
        }
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", session.modeId); mode.put("name", session.modeName); mode.put("slash_command", session.modeName);
        mode.put("description", "Active " + session.modeName + " loop."); mode.put("source", session.modeId.startsWith("custom:") ? "custom" : "builtin");
        return Map.of("state", session.state, "mode", mode);
    }
}
