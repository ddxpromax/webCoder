package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** One hook instance is attached to one agent turn. */
public class ApprovalHook implements Hook, RuntimeContextAware {
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
        "read_file", "list_directory", "search_code",
        "find_symbol", "grep_search", "glob_search",
        "git_status", "git_diff", "git_log",
        "get_current_time", "view_image", "view_video",
        "web_fetch", "web_search"
    );
    private static final Set<String> GUARDED_TOOLS = Set.of(
        "execute_shell_command", "write_file", "edit_file",
        "append_file", "git_commit", "git_add",
        "git_branch",
        "send_file_to_user", "browser_use",
        "desktop_screenshot", "execute_command", "bash"
    );

    private final String agentId;
    private final AgentStore agents;
    private final ApprovalStore store;
    private final ToolGuardService guard;
    private final FileGuardService fileGuard;
    private final ToolConfigService toolConfig;
    private final Path workspace;
    private final AtomicReference<RuntimeContext> context =
        new AtomicReference<>();

    public ApprovalHook(
        String agentId,
        AgentStore agents,
        ApprovalStore store,
        ToolGuardService guard,
        FileGuardService fileGuard,
        ToolConfigService toolConfig,
        Path workspace
    ) {
        this.agentId = agentId;
        this.agents = agents;
        this.store = store;
        this.guard = guard;
        this.fileGuard = fileGuard;
        this.toolConfig = toolConfig;
        this.workspace = workspace;
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        context.set(runtimeContext);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreActingEvent acting)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = acting.getToolUse();
        if (toolUse == null) {
            return Mono.just(event);
        }
        if (!toolConfig.isEnabled(agentId, toolUse.getName())) {
            acting.setToolUse(rejected(
                toolUse,
                "This tool is disabled for the current agent."
            ));
            return Mono.just(event);
        }
        String reason = guard.check(
            toolUse.getName(), toolUse.getInput()
        );
        if (reason == null) {
            reason = fileGuard.check(
                toolUse.getName(), toolUse.getInput(),
                workspace
            );
        }
        if (reason != null) {
            acting.setToolUse(rejected(toolUse, reason));
            return Mono.just(event);
        }
        if (!needsApproval(
            toolUse.getName(), toolUse.getInput()
        )) {
            return Mono.just(event);
        }
        return Mono.fromCallable(() -> {
            String sessionId = sessionId(event);
            ApprovalStore.ApprovalRequest request =
                store.register(
                    sessionId, sessionId, agentId,
                    toolUse.getName(), toolUse.getName(),
                    "HIGH", TIMEOUT_MS / 1000
                );
            String decision = request.await(TIMEOUT_MS);
            if (!"approved".equals(decision)) {
                store.resolve(request.requestId,
                    "denied", "timeout or cancellation"
                );
                acting.setToolUse(rejected(
                    toolUse, "User denied this tool call."
                ));
            }
            return event;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static ToolUseBlock rejected(
        ToolUseBlock original,
        String reason
    ) {
        return ToolUseBlock.builder()
            .id(original.getId())
            .name(original.getName())
            .input(original.getInput())
            .content(reason)
            .state(ToolCallState.FINISHED)
            .build();
    }

    @Override
    public int priority() {
        return 10;
    }

    private boolean needsApproval(
        String toolName,
        java.util.Map<String, Object> input
    ) {
        if ("git_branch".equals(toolName)
            && (input == null || input.get("name") == null
                || String.valueOf(input.get("name")).isBlank())) {
            return false;
        }
        var profile = agents.getProfile(agentId);
        String level = profile == null
            ? "AUTO"
            : String.valueOf(profile.getOrDefault(
                "approval_level", "AUTO"
            )).trim().toUpperCase();
        return switch (level) {
            case "OFF" -> false;
            case "STRICT" -> true;
            case "SMART" -> !READ_ONLY_TOOLS.contains(toolName);
            default -> GUARDED_TOOLS.contains(toolName);
        };
    }

    private String sessionId(HookEvent event) {
        RuntimeContext current = context.get();
        if (current != null
            && current.getSessionId() != null
            && !current.getSessionId().isBlank()) {
            return current.getSessionId();
        }
        try {
            var agent = event.getAgent();
            if (agent != null && agent.getAgentState() != null
                && agent.getAgentState().getSessionId() != null) {
                return agent.getAgentState().getSessionId();
            }
        } catch (Exception ignored) {
            // An unbound event still gets a distinct fallback session.
        }
        return "session-" + System.nanoTime();
    }
}
