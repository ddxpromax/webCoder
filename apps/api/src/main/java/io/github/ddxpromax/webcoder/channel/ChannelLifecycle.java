package io.github.ddxpromax.webcoder.channel;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Starts enabled channels after Spring finishes booting and stops them cleanly. */
@Component
public class ChannelLifecycle {
    private final AgentStore agents;
    private final ChannelRegistry registry;
    public ChannelLifecycle(AgentStore agents, ChannelRegistry registry) { this.agents = agents; this.registry = registry; }
    @EventListener(ApplicationReadyEvent.class) public void onReady() { registry.reload(agents.loadConfig()); }
    @PreDestroy public void stop() { registry.stopAll(); }
}
