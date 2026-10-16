package io.github.ddxpromax.webcoder.channel;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Persistent Majo `target=last` contact destination for proactive replies. */
@Component
public class LastContactStore {
    private final JsonFileStore files;
    private final java.nio.file.Path path;
    private final Object lock = new Object();
    private Map<String, Object> latest;

    public LastContactStore(MajoWorkingDirectory working, JsonFileStore files) {
        this.files = files;
        this.path = working.resolve("last_contact.json");
        this.latest = files.read(path, Map.of());
        if (this.latest.isEmpty()) {
            this.latest = null;
        }
    }

    public void record(String channel, String recipient, String userId) {
        if (blank(channel) || blank(recipient)) {
            return;
        }
        synchronized (lock) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("channel", channel);
            entry.put("to", recipient);
            entry.put("user_id", userId == null ? "" : userId);
            entry.put("updated_at", Instant.now().toEpochMilli() / 1000.0);
            latest = entry;
            files.writeAtomic(path, entry);
        }
    }

    public Map<String, Object> latest() {
        synchronized (lock) {
            return latest == null ? null : new LinkedHashMap<>(latest);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
