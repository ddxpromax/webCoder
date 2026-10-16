package io.github.ddxpromax.webcoder.agent;

import java.util.List;
import java.util.Map;

/** Pluggable long-term memory backend, following Majo's ADR-0008. */
public interface MemoryBackend {
    String id();
    void start(MemoryBackendContext context);
    void close();
    boolean isAvailable();
    String getMemoryPrompt();
    List<MemoryHit> search(String query, int maxResults);
    void remember(String content, Map<String, Object> metadata);

    default Map<String, Object> rebuild() {
        return Map.of("status", "unsupported");
    }
}
