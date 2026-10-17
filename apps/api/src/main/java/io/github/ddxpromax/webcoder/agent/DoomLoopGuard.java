package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Majo's per-turn protection against consecutive identical tool calls. */
@Component
public class DoomLoopGuard {
    static final int DEFAULT_WARN_AFTER = 3;
    static final int DEFAULT_STOP_AFTER = 6;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentStore agents;
    private final ConcurrentHashMap<String, State> sessions = new ConcurrentHashMap<>();
    private static final class State { String tool; String hash; int count; }
    public DoomLoopGuard(AgentStore agents) { this.agents = agents; }

    public void reset(String sessionId) { if (sessionId != null && !sessionId.isBlank()) sessions.remove(sessionId); }
    public String check(String sessionId, String tool, Map<String, Object> input) {
        Map<String, Object> config = section();
        if (!bool(config.get("enabled"), true) || sessionId == null || sessionId.isBlank() || tool == null || tool.isBlank()) return null;
        int warn = number(config.get("warn_after"), DEFAULT_WARN_AFTER); int stop = number(config.get("stop_after"), DEFAULT_STOP_AFTER); if (stop <= warn) stop = warn + 1;
        if (sessions.size() > 1000) sessions.clear();
        String hash = hash(input); State state = sessions.computeIfAbsent(sessionId, key -> new State());
        synchronized (state) {
            if (!tool.equals(state.tool) || !hash.equals(state.hash)) { state.tool = tool; state.hash = hash; state.count = 1; return null; }
            state.count++;
            if (state.count >= stop) return "Doom-loop protection stopped tool '" + tool + "' after " + state.count + " identical calls. Stop repeating, summarize the current progress, and explain the blocker to the user.";
            if (state.count >= warn) return "Doom-loop protection blocked tool '" + tool + "' after " + state.count + " identical calls. Change the approach or adjust the arguments before trying again.";
            return null;
        }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> section() { Object security = agents.loadConfig().get("security"); if (security instanceof Map<?, ?> map && map.get("doom_loop") instanceof Map<?, ?> doom) return new LinkedHashMap<>((Map<String, Object>) doom); return Map.of(); }
    static String hash(Map<String, Object> input) { try { String value = JSON.writeValueAsString(input == null ? Map.of() : input); if (value.length() > 2048) value = value.substring(0, 2048); byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(bytes); } catch (Exception exception) { return String.valueOf(System.identityHashCode(input)); } }
    private static int number(Object value, int fallback) { try { int result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); return result > 0 ? result : fallback; } catch (Exception ignored) { return fallback; } }
    private static boolean bool(Object value, boolean fallback) { return value == null ? fallback : value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value)); }
}
