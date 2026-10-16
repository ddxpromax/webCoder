package io.github.ddxpromax.webcoder.agent;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CodingModeService {
    private final AgentStore agents;

    public CodingModeService(AgentStore agents) {
        this.agents = agents;
    }

    public boolean isEnabled(String agentId) {
        Map<String, Object> profile = agents.getProfile(agentId);
        if (profile == null) {
            return false;
        }
        Object raw = profile.get("coding_mode");
        return raw instanceof Map<?, ?> map
            && Boolean.TRUE.equals(map.get("enabled"));
    }

    public static String disabledReply() {
        return "Coding Mode is disabled. Enable it in the sidebar and try again.";
    }
}
