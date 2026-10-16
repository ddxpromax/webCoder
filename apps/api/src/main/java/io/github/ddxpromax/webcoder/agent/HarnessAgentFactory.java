package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.model.ProviderModelRepository;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import io.github.ddxpromax.webcoder.skill.MaterializeSkillTool;
import io.github.ddxpromax.webcoder.skill.SkillWorkspaceService;
import io.github.ddxpromax.webcoder.mcp.McpRuntime;
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
    private final SubagentService subagents;
    private final MemoryBackendRegistry memoryBackends;
    private final MemoryWritePipeline memoryWrites;
    private final ToolConfigService toolConfig;
    private final TokenUsageService tokenUsage;
    private final SkillWorkspaceService skills;
    private final WorkspaceMutationGate mutationGate;
    private final McpRuntime mcpRuntime;

    public HarnessAgentFactory(
        ModelRoutingService routing,
        ProviderModelRepository providerModels,
        SettingsService settings,
        AgentStore agents,
        ApprovalStore approvals,
        ToolGuardService toolGuard,
        FileGuardService fileGuard,
        CodingModeService codingMode,
        MajoWorkingDirectory workingDirectory,
        SubagentService subagents,
        MemoryBackendRegistry memoryBackends,
        MemoryWritePipeline memoryWrites,
        ToolConfigService toolConfig,
        TokenUsageService tokenUsage,
        SkillWorkspaceService skills,
        WorkspaceMutationGate mutationGate,
        McpRuntime mcpRuntime
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
        this.subagents = subagents;
        this.memoryBackends = memoryBackends;
        this.memoryWrites = memoryWrites;
        this.toolConfig = toolConfig;
        this.tokenUsage = tokenUsage;
        this.skills = skills;
        this.mutationGate = mutationGate;
        this.mcpRuntime = mcpRuntime;
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
        Toolkit toolkit = toolkitFor(agentId, workspace);
        return HarnessAgent.builder()
            .name(agentName(agentId))
            .agentId(agentId)
            .sysPrompt(ProtectedPrompt.withContract(systemPrompt))
            .model(model)
            .toolkit(toolkit)
            .workspace(workspace)
            .skillFilter(SkillFilter.only(
                skills.enabledNames(workspace).toArray(String[]::new)
            ))
            .hook(new SystemPromptFilesHook(
                agents.workspaceDirForAgent(agentId)
            ))
            .hook(new MemoryWriteHook(
                agentId, memoryWrites
            ))
            .hook(new CodingModePromptInjector(
                codingMode, agentId, workspace
            ))
            .hook(new ModelRequestNormalizerHook(
                agentId, routing, providerModels, agents,
                memoryBackends
            ))
            .hook(new ApprovalHook(
                agentId, agents, approvals, toolGuard,
                fileGuard, toolConfig, workspace,
                mcpRuntime, toolkit
            ));
    }

    public void close(HarnessAgent agent) {
        try {
            mcpRuntime.close(agent.getToolkit());
        } finally {
            agent.close();
        }
    }

    Toolkit toolkitFor(String agentId, Path workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkspaceTools(workspace, mutationGate));
        toolkit.registerTool(new ShellTool(workspace, mutationGate));
        toolkit.registerTool(new GitTools(workspace, mutationGate));
        toolkit.registerTool(new SubagentTools(
            agentId, workspace, subagents
        ));
        toolkit.registerTool(new MemorySearchTool(
            agentId, memoryBackends
        ));
        toolkit.registerTool(new TimeTools(agents));
        toolkit.registerTool(new TokenUsageTool(agentId, tokenUsage));
        toolkit.registerTool(new MaterializeSkillTool(
            workspace, skills
        ));
        toolkit.registerTool(new WebTools());
        toolkit.registerTool(new SendFileToUserTool(
            workspace, workingDirectory
        ));
        mcpRuntime.register(toolkit);
        return toolkit;
    }
}
