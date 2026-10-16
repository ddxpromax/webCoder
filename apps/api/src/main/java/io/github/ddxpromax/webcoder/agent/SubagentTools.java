package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.util.Map;

/** Majo's subagent tools, scoped to the calling turn's workspace. */
public class SubagentTools {
    private final String agentId;
    private final Path workspace;
    private final SubagentService service;

    public SubagentTools(
        String agentId,
        Path workspace,
        SubagentService service
    ) {
        this.agentId = agentId;
        this.workspace = workspace;
        this.service = service;
    }

    @Tool(
        name = "spawn_subagent",
        description = "Spawn an ephemeral subagent in the current workspace and wait for its result"
    )
    public String spawnSubagent(
        @ToolParam(name = "task", description = "Task for the subagent")
        String task
    ) {
        if (task == null || task.isBlank()) {
            return "task must not be empty";
        }
        return service.spawn(agentId, workspace, task);
    }

    @Tool(
        name = "chat_with_agent",
        description = "Send a message to another configured agent and wait for its reply"
    )
    public String chatWithAgent(
        @ToolParam(name = "agent_name", description = "Target agent ID")
        String agentName,
        @ToolParam(name = "message", description = "Message to send")
        String message
    ) {
        if (agentName == null || agentName.isBlank()) {
            return "agent_name must not be empty";
        }
        if (message == null || message.isBlank()) {
            return "message must not be empty";
        }
        return service.chatWithAgent(agentName, message);
    }

    @Tool(
        name = "submit_to_agent",
        description = "Submit a background task to another configured agent and return its task ID"
    )
    public String submitToAgent(
        @ToolParam(name = "agent_name", description = "Target agent ID")
        String agentName,
        @ToolParam(name = "task", description = "Background task")
        String task
    ) {
        if (agentName == null || agentName.isBlank()) {
            return "agent_name must not be empty";
        }
        if (task == null || task.isBlank()) {
            return "task must not be empty";
        }
        return "Task submitted. task_id: "
            + service.submit(agentName, task)
            + ". Use check_agent_task to get the result.";
    }

    @Tool(
        name = "check_agent_task",
        description = "Check the status and result of a background agent task",
        readOnly = true,
        concurrencySafe = true
    )
    public String checkAgentTask(
        @ToolParam(name = "task_id", description = "Task ID returned by submit_to_agent")
        String taskId
    ) {
        if (taskId == null || taskId.isBlank()) {
            return "task_id must not be empty";
        }
        Map<String, Object> task = service.check(taskId);
        String status = String.valueOf(task.get("status"));
        return switch (status) {
            case "completed" -> "Task " + taskId
                + " completed.\nResult: " + task.get("result");
            case "failed" -> "Task " + taskId
                + " failed: " + task.get("error");
            case "cancelled" -> "Task " + taskId
                + " was cancelled.";
            case "running" -> "Task " + taskId
                + " is still running. Check again later.";
            default -> "Task " + taskId
                + " does not exist or has expired.";
        };
    }
}
