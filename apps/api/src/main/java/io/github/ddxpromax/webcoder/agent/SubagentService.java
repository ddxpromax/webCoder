package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Runs Majo's four cooperative-agent tools in independent agent sessions. */
@Service
public class SubagentService {
    private static final Logger log = LoggerFactory.getLogger(SubagentService.class);
    private static final String SYSTEM_PROMPT =
        "You are a coding assistant executing a delegated task. "
        + "Use the available tools to finish the task, then summarize the result concisely.";
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_RESULT_CHARS = 16_000;

    private final ObjectProvider<HarnessAgentFactory> factories;
    private final AgentStore agents;
    private final SubagentTaskRegistry registry;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "majo-subagent");
        thread.setDaemon(true);
        return thread;
    });

    public SubagentService(
        ObjectProvider<HarnessAgentFactory> factories,
        AgentStore agents,
        SubagentTaskRegistry registry
    ) {
        this.factories = factories;
        this.agents = agents;
        this.registry = registry;
    }

    public String spawn(String agentId, Path parentWorkspace, String task) {
        Path subDir;
        try {
            subDir = parentWorkspace.resolve(".subagents")
                .resolve(UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 12));
            Files.createDirectories(subDir);
        } catch (Exception exception) {
            return "Cannot create subagent workspace: "
                + exception.getMessage();
        }
        try {
            HarnessAgent agent = buildAgent(agentId, subDir);
            if (agent == null) {
                return "No active model is configured for the subagent.";
            }
            return runAgent(agent, task,
                "subagent-" + UUID.randomUUID());
        } catch (Exception exception) {
            log.warn("Subagent task failed", exception);
            return "Subagent task failed: " + exception.getMessage();
        }
    }

    public String chatWithAgent(String agentId, String message) {
        Path workspace;
        try {
            workspace = agents.workspaceDirForAgent(agentId);
        } catch (Exception exception) {
            return "Agent '" + agentId + "' was not found.";
        }
        try {
            HarnessAgent agent = buildAgent(agentId, workspace);
            if (agent == null) {
                return "Agent '" + agentId
                    + "' has no active model configured.";
            }
            return runAgent(agent, message,
                "chat-" + UUID.randomUUID());
        } catch (Exception exception) {
            log.warn("Chat with agent {} failed", agentId,
                exception);
            return "Chat with agent '" + agentId + "' failed: "
                + exception.getMessage();
        }
    }

    public String submit(String agentId, String task) {
        SubagentTaskRegistry.Task entry = registry.register(agentId);
        executor.submit(() -> {
            try {
                Path workspace = agents.workspaceDirForAgent(agentId);
                HarnessAgent agent = buildAgent(agentId, workspace);
                if (agent == null) {
                    registry.fail(entry.taskId,
                        "Agent '" + agentId
                            + "' has no active model configured.");
                    return;
                }
                entry.agent = agent;
                if (!"running".equals(entry.status)) {
                    agent.interrupt();
                    return;
                }
                registry.complete(entry.taskId,
                    runAgent(agent, task, "task-" + entry.taskId));
            } catch (Exception exception) {
                log.warn("Background agent task {} failed",
                    entry.taskId, exception);
                registry.fail(entry.taskId,
                    exception.getMessage());
            } finally {
                entry.agent = null;
            }
        });
        return entry.taskId;
    }

    public Map<String, Object> check(String taskId) {
        SubagentTaskRegistry.Task task = registry.get(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (task == null) {
            result.put("status", "not_found");
            result.put("detail", "Task does not exist or has expired: "
                + taskId);
            return result;
        }
        result.put("task_id", task.taskId);
        result.put("agent_id", task.agentId);
        result.put("status", task.status);
        result.put("result", task.result);
        result.put("error", task.error);
        result.put("progress", task.progress);
        return result;
    }

    private HarnessAgent buildAgent(String agentId, Path workspace) {
        HarnessAgentFactory factory = factories.getObject();
        var model = factory.modelFor(agentId,
            HarnessAgentFactory.ModelFallback.NONE);
        return model == null ? null
            : factory.builder(agentId, SYSTEM_PROMPT,
                workspace, model).build();
    }

    private String runAgent(
        HarnessAgent agent,
        String prompt,
        String sessionId
    ) {
        RuntimeContext context = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId("subagent")
            .build();
        StreamTextCollector output = new StreamTextCollector();
        agent.streamEvents(new UserMessage(prompt), context)
            .doOnNext(output::accept)
            .blockLast(RUN_TIMEOUT);
        String result = output.text().trim();
        if (result.isBlank() && !output.thinking().isBlank()) {
            result = "[Reasoning only] "
                + output.thinking().trim();
        }
        if (result.isBlank()) {
            return "(No text output from subagent)";
        }
        return result.length() > MAX_RESULT_CHARS
            ? result.substring(0, MAX_RESULT_CHARS)
                + "\n...[result truncated]"
            : result;
    }
}
