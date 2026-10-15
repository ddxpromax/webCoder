package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HarnessAgentFactory {
    public enum ModelFallback {
        SETTINGS,
        NONE
    }

    private final ModelRoutingService routing;
    private final SettingsService settings;
    private final AgentStore agents;
    private final ApprovalStore approvals;
    private final MajoWorkingDirectory workingDirectory;

    public HarnessAgentFactory(
        ModelRoutingService routing,
        SettingsService settings,
        AgentStore agents,
        ApprovalStore approvals,
        MajoWorkingDirectory workingDirectory
    ) {
        this.routing = routing;
        this.settings = settings;
        this.agents = agents;
        this.approvals = approvals;
        this.workingDirectory = workingDirectory;
    }

    public String agentName(String agentId) {
        Map<String, Object> profile = agents.getProfile(agentId);
        if (profile != null && profile.get("name") != null
            && !String.valueOf(profile.get("name")).isBlank()) {
            return String.valueOf(profile.get("name"));
        }
        return agentId;
    }

    public Path workspaceFor(String agentId) {
        try {
            return agents.workspaceDirForAgent(agentId);
        } catch (RuntimeException exception) {
            return workingDirectory.root();
        }
    }

    public OpenAIChatModel modelFor(
        String agentId,
        ModelFallback fallback
    ) {
        var slot = routing.resolveEffectiveModel(agentId);
        if (slot != null && slot.hasBoth()) {
            return routing.buildOpenAIChatModel(
                slot.providerId(), slot.modelId()
            );
        }
        if (fallback == ModelFallback.SETTINGS) {
            Map<String, String> legacy = settings.getSettings();
            return OpenAIChatModel.builder()
                .apiKey(legacy.get("apiKey"))
                .baseUrl(legacy.get("baseUrl"))
                .modelName(legacy.get("modelName"))
                .build();
        }
        return null;
    }

    public HarnessAgent.Builder builder(
        String agentId,
        String systemPrompt,
        Path workspace,
        OpenAIChatModel model
    ) {
        return HarnessAgent.builder()
            .name(agentName(agentId))
            .agentId(agentId)
            .sysPrompt(ProtectedPrompt.withContract(systemPrompt))
            .model(model)
            .toolkit(toolkitFor(workspace))
            .workspace(workspace)
            .hook(new ApprovalHook(agentId, agents, approvals));
    }

    Toolkit toolkitFor(Path workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkspaceTools(workspace));
        return toolkit;
    }
}
