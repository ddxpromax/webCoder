package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMutationGateTests {
    @TempDir
    Path temporary;

    @Test
    void writesInOneRepositoryWaitWhileAnotherRepositoryAndReadsProceed()
        throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path nested = Files.createDirectory(first.resolve("nested"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Files.createDirectory(first.resolve(".git"));
        Files.createDirectory(second.resolve(".git"));
        Files.writeString(nested.resolve("existing.txt"), "readable");

        WorkspaceMutationGate gate = new LocalWorkspaceMutationGate();
        WorkspaceTools nestedTools = new WorkspaceTools(nested, gate);
        WorkspaceTools secondTools = new WorkspaceTools(second, gate);
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var held = gate.acquire(first);
            java.util.concurrent.Future<String> waiting;
            try {
                waiting = executor.submit(() -> {
                    started.countDown();
                    return nestedTools.writeFile("written.txt", "first");
                });
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondTools.writeFile("written.txt", "second"))
                    .contains("[created]");
                assertThat(nestedTools.readFile(
                    "existing.txt", null, null
                )).contains("readable");
                assertThat(waiting.isDone()).isFalse();
            } finally {
                held.close();
            }
            assertThat(waiting.get(2, TimeUnit.SECONDS))
                .contains("[created]");
            assertThat(Files.readString(
                nested.resolve("written.txt")
            )).isEqualTo("first");
        }
    }
}
