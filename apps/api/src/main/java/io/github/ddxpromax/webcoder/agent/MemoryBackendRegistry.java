package io.github.ddxpromax.webcoder.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Selects Majo memory backends per agent, with keyword fallback. */
@Service
public class MemoryBackendRegistry {
    private static final Logger log =
        LoggerFactory.getLogger(MemoryBackendRegistry.class);

    private final AgentStore agents;
    private final MemorySummarizer summarizer;
    private final ModelRoutingService routing;
    private final SettingsService settings;
    private final EmbeddingClient embeddingClient;
    private final Map<String, Supplier<MemoryBackend>> backends =
        new LinkedHashMap<>();
    private final ConcurrentHashMap<String, MemoryBackend> active =
        new ConcurrentHashMap<>();

    public MemoryBackendRegistry(
        AgentStore agents,
        MemorySummarizer summarizer,
        ModelRoutingService routing,
        SettingsService settings,
        EmbeddingClient embeddingClient
    ) {
        this.agents = agents;
        this.summarizer = summarizer;
        this.routing = routing;
        this.settings = settings;
        this.embeddingClient = embeddingClient;
        backends.put("keyword", KeywordMemoryBackend::new);
        backends.put("summary", () -> new SummaryMemoryBackend(
            this.summarizer
        ));
        backends.put("embedding", () -> new EmbeddingMemoryBackend(
            this.routing, this.settings, this.embeddingClient
        ));
    }

    public List<String> backendIds() {
        return List.copyOf(backends.keySet());
    }

    public MemoryBackend resolve(String agentId) {
        MemoryBackend cached = active.get(agentId);
        if (cached != null && cached.isAvailable()) {
            return cached;
        }
        return active.compute(agentId, (id, current) -> {
            if (current != null && current.isAvailable()) {
                return current;
            }
            if (current != null) {
                current.close();
            }
            String configured = configuredBackendId(agentId);
            Supplier<MemoryBackend> factory = backends.get(configured);
            if (factory == null) {
                log.warn("Memory backend '{}' is not registered; using keyword",
                    configured);
                factory = backends.get("keyword");
            }
            MemoryBackend backend = factory.get();
            Map<String, Object> profile = agents.getProfile(agentId);
            if (profile == null) {
                return null;
            }
            try {
                backend.start(new MemoryBackendContext(
                    agentId,
                    agents.workspaceDirForAgent(agentId),
                    memoryConfig(agentId),
                    String.valueOf(profile.getOrDefault("language", "en"))
                ));
                return backend.isAvailable() ? backend : null;
            } catch (Exception exception) {
                log.warn("Cannot start memory for agent '{}'",
                    agentId, exception);
                return null;
            }
        });
    }

    public void evict(String agentId) {
        MemoryBackend backend = active.remove(agentId);
        if (backend != null) {
            backend.close();
        }
    }

    public String configuredBackendId(String agentId) {
        Object value = agents.getRunningConfig(agentId)
            .get("memory_manager_backend");
        String id = value == null ? "" : String.valueOf(value)
            .trim().toLowerCase();
        return id.isBlank() || "remelight".equals(id)
            ? "keyword" : id;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> memoryConfig(String agentId) {
        Object value = agents.getRunningConfig(agentId)
            .get("reme_light_memory_config");
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : Map.of();
    }
}
