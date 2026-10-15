package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.model.ProviderModelRepository;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HarnessAgentFactory {
    public enum ModelFallback {
        SETTINGS,
        NONE
    }

    private final ModelRoutingService routing;
    private final ProviderModelRepository providerModels;
    private final SettingsService settings;
    private final AgentStore agents;
    private final ApprovalStore approvals;
    private final ToolGuardService toolGuard;
    private final FileGuardService fileGuard;
    private final CodingModeService codingMode;
    private final MajoWorkingDirectory workingDirectory;

    public HarnessAgentFactory(
        ModelRoutingService routing,
        ProviderModelRepository providerModels,
        SettingsService settings,
        AgentStore agents,
        ApprovalStore approvals,
        ToolGuardService toolGuard,
        FileGuardService fileGuard,
        CodingModeService codingMode,
        MajoWorkingDirectory workingDirectory
    ) {
        this.routing = routing;
        this.providerModels = providerModels;
        this.settings = settings;
        this.agents = agents;
        this.approvals = approvals;
        this.toolGuard = toolGuard;
        this.fileGuard = fileGuard;
        this.codingMode = codingMode;
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
        try {
            Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot create agent workspace: " + workspace,
                exception
            );
        }
        return HarnessAgent.builder()
            .name(agentName(agentId))
            .agentId(agentId)
            .sysPrompt(ProtectedPrompt.withContract(systemPrompt))
            .model(model)
            .toolkit(toolkitFor(workspace))
            .workspace(workspace)
            .hook(new SystemPromptFilesHook(
                agents.workspaceDirForAgent(agentId)
            ))
            .hook(new CodingModePromptInjector(
                codingMode, agentId, workspace
            ))
            .hook(new ModelRequestNormalizerHook(
                agentId, routing, providerModels
            ))
            .hook(new ApprovalHook(
                agentId, agents, approvals, toolGuard,
                fileGuard, workspace
            ));
    }

    Toolkit toolkitFor(Path workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkspaceTools(workspace));
        toolkit.registerTool(new ShellTool(workspace));
        toolkit.registerTool(new GitTools(workspace));
        return toolkit;
    }
}
