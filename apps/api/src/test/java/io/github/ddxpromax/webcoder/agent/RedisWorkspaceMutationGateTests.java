package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisWorkspaceMutationGateTests {
    @TempDir
    Path temporary;

    @Test
    void separateInstancesSerializeWritesToOneRepository()
        throws Exception {
        try (GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
        ).withExposedPorts(6379)) {
            redis.start();
            LettuceConnectionFactory connection =
                new LettuceConnectionFactory(
                    redis.getHost(), redis.getMappedPort(6379)
                );
            connection.afterPropertiesSet();
            try {
                StringRedisTemplate template =
                    new StringRedisTemplate(connection);
                template.afterPropertiesSet();
                Path root = Files.createDirectory(
                    temporary.resolve("repository")
                );
                Files.createDirectory(root.resolve(".git"));
                Path nested = Files.createDirectory(
                    root.resolve("nested")
                );
                RedisWorkspaceMutationGate first =
                    new RedisWorkspaceMutationGate(template);
                RedisWorkspaceMutationGate second =
                    new RedisWorkspaceMutationGate(template);
                CountDownLatch started = new CountDownLatch(1);
                try (var executor =
                         Executors.newVirtualThreadPerTaskExecutor()) {
                    var held = first.acquire(root);
                    java.util.concurrent.Future<Boolean> waiting;
                    try {
                        waiting = executor.submit(() -> {
                            started.countDown();
                            try (var ignored = second.acquire(nested)) {
                                return true;
                            }
                        });
                        assertThat(started.await(2, TimeUnit.SECONDS))
                            .isTrue();
                        assertThat(waiting.isDone()).isFalse();
                    } finally {
                        held.close();
                    }
                    assertThat(waiting.get(5, TimeUnit.SECONDS))
                        .isTrue();
                }
            } finally {
                connection.destroy();
            }
        }
    }
}
