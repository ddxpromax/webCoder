package io.github.ddxpromax.webcoder.skill;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * In-memory lifecycle registry for Majo Hub installation tasks.
 *
 * <p>Terminal tasks remain observable for ten minutes. The history cap keeps
 * a busy server from retaining unbounded task metadata. Download/import code
 * receives the task cancellation flag and checks it between blocking calls.</p>
 */
@Service
public class SkillHubTaskService {
    static final long TTL_MILLIS = 10 * 60 * 1_000L;
    static final int MAX_HISTORY = 100;
    private static final Set<String> TERMINAL = Set.of(
        "completed", "failed", "cancelled"
    );

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Clock clock;

    public SkillHubTaskService() {
        this(Clock.systemUTC());
    }

    SkillHubTaskService(Clock clock) {
        this.clock = clock;
    }

    public synchronized Task create(
        String bundleUrl,
        String version,
        boolean enable
    ) {
        cleanup();
        Task task = new Task(
            UUID.randomUUID().toString(), bundleUrl,
            version == null ? "" : version, enable, now()
        );
        tasks.put(task.taskId(), task);
        return task;
    }

    public synchronized Task get(String taskId) {
        cleanup();
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Install task not found");
        }
        return task;
    }

    public synchronized AtomicBoolean cancellation(String taskId) {
        return get(taskId).cancelled();
    }

    public synchronized void importing(String taskId) {
        Task task = get(taskId);
        if (!task.terminal()) {
            task.setStatus("importing", now());
        }
    }

    public synchronized void finish(
        String taskId,
        String status,
        String error,
        Map<String, Object> result
    ) {
        if (!TERMINAL.contains(status)) {
            throw new IllegalArgumentException("Invalid terminal task status");
        }
        Task task = get(taskId);
        if (!task.terminal() || ("cancelled".equals(task.status())
            && "cancelled".equals(status))) {
            task.finish(status, error, result, now());
        }
        cleanup();
    }

    /** Cancelling a terminal task is intentionally idempotent. */
    public synchronized String cancel(String taskId) {
        Task task = get(taskId);
        if (!task.terminal()) {
            task.cancelled().set(true);
            task.setStatus("cancelled", now());
        }
        return task.status();
    }

    synchronized void cleanup() {
        long now = now();
        tasks.entrySet().removeIf(entry -> {
            Task task = entry.getValue();
            return task.terminal()
                && now - task.updatedAt() > TTL_MILLIS;
        });
        int excess = tasks.size() - MAX_HISTORY;
        if (excess <= 0) {
            return;
        }
        List<Task> oldest = new ArrayList<>(tasks.values());
        oldest.sort(Comparator.comparingLong(Task::updatedAt));
        for (int index = 0; index < excess; index++) {
            tasks.remove(oldest.get(index).taskId());
        }
    }

    private long now() {
        return clock.millis();
    }

    public static final class Task {
        private final String taskId;
        private final String bundleUrl;
        private final String version;
        private final boolean enable;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long createdAt;
        private volatile String status = "pending";
        private volatile String error;
        private volatile Map<String, Object> result;
        private volatile long updatedAt;

        Task(
            String taskId,
            String bundleUrl,
            String version,
            boolean enable,
            long createdAt
        ) {
            this.taskId = taskId;
            this.bundleUrl = bundleUrl;
            this.version = version;
            this.enable = enable;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        public String taskId() { return taskId; }
        public String bundleUrl() { return bundleUrl; }
        public String version() { return version; }
        public boolean enable() { return enable; }
        public String status() { return status; }
        public String error() { return error; }
        public Map<String, Object> result() { return result; }
        public long createdAt() { return createdAt; }
        public long updatedAt() { return updatedAt; }
        public AtomicBoolean cancelled() { return cancelled; }

        boolean terminal() {
            return TERMINAL.contains(status);
        }

        void setStatus(String next, long timestamp) {
            status = next;
            updatedAt = timestamp;
        }

        void finish(
            String next,
            String issue,
            Map<String, Object> data,
            long timestamp
        ) {
            status = next;
            error = issue;
            result = data;
            updatedAt = timestamp;
        }
    }
}
