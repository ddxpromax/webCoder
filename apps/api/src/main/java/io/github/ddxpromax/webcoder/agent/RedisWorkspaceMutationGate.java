package io.github.ddxpromax.webcoder.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis SET NX lease with owner-checked Lua release. */
@Component
@ConditionalOnProperty(
    name = "webcoder.redis-locks.enabled",
    havingValue = "true"
)
public class RedisWorkspaceMutationGate
    implements WorkspaceMutationGate {
    private static final Duration LEASE_TIME =
        Duration.ofSeconds(150);
    private static final long WAIT_MS = 30_000;
    private static final DefaultRedisScript<Long> RELEASE =
        new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                + "then return redis.call('del', KEYS[1]) "
                + "else return 0 end",
            Long.class
        );

    private final StringRedisTemplate redis;

    public RedisWorkspaceMutationGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Lease acquire(Path workspace) {
        String key = keyFor(workspace);
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime()
            + Duration.ofMillis(WAIT_MS).toNanos();
        do {
            if (Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, owner, LEASE_TIME))) {
                return () -> redis.execute(
                    RELEASE, List.of(key), owner
                );
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Workspace write was interrupted", exception
                );
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(
            "Workspace is busy with another write operation"
        );
    }

    static String keyFor(Path workspace) {
        String path = WorkspaceMutationGate.repositoryRoot(
            workspace
        ).toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(path.getBytes(StandardCharsets.UTF_8));
            return "webcoder:workspace:write:"
                + HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
