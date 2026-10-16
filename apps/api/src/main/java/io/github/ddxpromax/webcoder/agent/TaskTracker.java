package io.github.ddxpromax.webcoder.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TaskTracker {
    private final ConcurrentHashMap<String, String> status =
        new ConcurrentHashMap<>();
    private volatile long lastRunAt;
    private volatile long lastFinishAt;

    public void setRunning(String chatId) {
        if (chatId != null && !chatId.isBlank()) {
            status.put(chatId, "running");
            lastRunAt = System.currentTimeMillis();
        }
    }

    public void setDone(String chatId) {
        if (chatId != null) {
            status.remove(chatId);
            lastFinishAt = System.currentTimeMillis();
        }
    }

    public String getStatus(String chatId) {
        return chatId == null ? "idle"
            : status.getOrDefault(chatId, "idle");
    }

    public Map<String, Object> getGlobalStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.isEmpty() ? "idle" : "running");
        result.put("running_task_count", status.size());
        result.put("last_run_at", lastRunAt == 0 ? null
            : Instant.ofEpochMilli(lastRunAt).toString());
        result.put("last_finish_at", lastFinishAt == 0 ? null
            : Instant.ofEpochMilli(lastFinishAt).toString());
        return result;
    }
}
