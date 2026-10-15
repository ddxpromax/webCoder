package io.github.ddxpromax.webcoder.agent;

import io.agentscope.harness.agent.HarnessAgent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Majo's short-lived registry for submit_to_agent tasks. */
@Component
public class SubagentTaskRegistry {
    public static final long TTL_MINUTES = 30;
    public static final int MAX_ENTRIES = 200;

    public static final class Task {
        public final String taskId;
        public final String agentId;
        public final Instant createdAt = Instant.now();
        public volatile String status = "running";
        public volatile String result;
        public volatile String error;
        public volatile String progress;
        public volatile HarnessAgent agent;

        private Task(String taskId, String agentId) {
            this.taskId = taskId;
            this.agentId = agentId;
        }
    }

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    public Task register(String agentId) {
        sweep();
        String id = "task_" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 12);
        Task task = new Task(id, agentId);
        tasks.put(id, task);
        return task;
    }

    public Task get(String taskId) {
        return tasks.get(taskId);
    }

    public void complete(String taskId, String result) {
        Task task = get(taskId);
        if (task != null && "running".equals(task.status)) {
            task.result = result;
            task.status = "completed";
            task.agent = null;
        }
    }

    public void fail(String taskId, String error) {
        Task task = get(taskId);
        if (task != null && "running".equals(task.status)) {
            task.error = error;
            task.status = "failed";
            task.agent = null;
        }
    }

    public void updateProgress(String taskId, String progress) {
        Task task = get(taskId);
        if (task != null && "running".equals(task.status)) {
            task.progress = progress;
        }
    }

    public boolean cancel(String taskId) {
        Task task = get(taskId);
        if (task == null || !"running".equals(task.status)) {
            return false;
        }
        task.status = "cancelled";
        HarnessAgent agent = task.agent;
        if (agent != null) {
            agent.interrupt();
            task.agent = null;
        }
        return true;
    }

    public List<Task> list(String status) {
        sweep();
        return tasks.values().stream()
            .filter(task -> status == null || status.equals(task.status))
            .sorted(Comparator.comparing(task -> task.createdAt))
            .toList();
    }

    public int size() {
        return tasks.size();
    }

    public int sweep() {
        Instant cutoff = Instant.now().minusSeconds(TTL_MINUTES * 60);
        int removed = 0;
        for (Task task : tasks.values()) {
            if (!"running".equals(task.status)
                && task.createdAt.isBefore(cutoff)
                && tasks.remove(task.taskId, task)) {
                removed++;
            }
        }
        if (tasks.size() > MAX_ENTRIES) {
            List<Task> oldest = new ArrayList<>(tasks.values());
            oldest.sort(Comparator.comparing(task -> task.createdAt));
            for (Task task : oldest) {
                if (tasks.size() <= MAX_ENTRIES) {
                    break;
                }
                if (!"running".equals(task.status)
                    && tasks.remove(task.taskId, task)) {
                    removed++;
                }
            }
        }
        return removed;
    }
}
