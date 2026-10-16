package io.github.ddxpromax.webcoder.channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Owns Majo channel adapters and reconciles their lifecycle with configuration. */
@Service
public class ChannelRegistry {
    private final Map<String, Channel> adapters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> configs = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final ChannelDispatcher dispatcher;

    public ChannelRegistry(ChannelDispatcher dispatcher, List<Channel> channels) {
        this.dispatcher = dispatcher;
        channels.forEach(channel -> adapters.put(channel.id(), channel));
    }

    public List<String> channelIds() { return adapters.keySet().stream().sorted().toList(); }
    public boolean contains(String id) { return adapters.containsKey(id); }
    public boolean isRunning(String id) { Channel channel = adapters.get(id); return channel != null && channel.isRunning(); }
    public String lastError(String id) { return errors.getOrDefault(id, ""); }
    public Map<String, Object> configFor(String id) { return new LinkedHashMap<>(configs.getOrDefault(id, Map.of())); }

    public synchronized void reload(Map<String, Object> allConfigs) {
        for (String id : channelIds()) restartInternal(id, config(allConfigs == null ? null : allConfigs.get(id)), false);
    }

    public synchronized void restart(String id, Map<String, Object> config) { restartInternal(id, config(config), true); }

    public synchronized void stopAll() {
        for (Channel channel : adapters.values()) {
            try { channel.stop(); } catch (Exception ignored) { }
        }
    }

    private void restartInternal(String id, Map<String, Object> config, boolean restart) {
        Channel channel = adapters.get(id);
        if (channel == null) return;
        configs.put(id, config);
        try {
            boolean enabled = Boolean.TRUE.equals(config.get("enabled"));
            if (channel.isRunning() && (restart || !enabled)) channel.stop();
            if (enabled && !channel.isRunning()) channel.start(config, dispatcher);
            errors.remove(id);
        } catch (Exception exception) {
            errors.put(id, exception.getMessage() == null ? "Unable to start channel." : exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }
}
