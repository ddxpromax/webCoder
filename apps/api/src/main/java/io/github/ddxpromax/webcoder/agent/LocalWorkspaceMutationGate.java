package io.github.ddxpromax.webcoder.agent;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-process counterpart used without the Redis deployment profile. */
@Component
@ConditionalOnProperty(
    name = "webcoder.redis-locks.enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class LocalWorkspaceMutationGate
    implements WorkspaceMutationGate {
    private static final LocalWorkspaceMutationGate SHARED =
        new LocalWorkspaceMutationGate();
    private final ConcurrentHashMap<Path, ReentrantLock> locks =
        new ConcurrentHashMap<>();

    public static LocalWorkspaceMutationGate shared() {
        return SHARED;
    }

    @Override
    public Lease acquire(Path workspace) {
        Path root = WorkspaceMutationGate.repositoryRoot(workspace);
        ReentrantLock lock = locks.computeIfAbsent(
            root, ignored -> new ReentrantLock(true)
        );
        try {
            if (!lock.tryLock(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Workspace is busy with another write operation"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Workspace write was interrupted", exception
            );
        }
        return lock::unlock;
    }
}
