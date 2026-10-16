package io.github.ddxpromax.webcoder.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class CheckpointServiceTests {
    @Test
    void snapshotsWorkspaceIntoAnIsolatedGitRepository() throws Exception {
        Path workspace = Files.createTempDirectory("majo-checkpoint-");
        try {
            Files.writeString(workspace.resolve("notes.txt"), "first version");
            CheckpointService service = new CheckpointService(new ObjectMapper());
            var snapshot = service.snapshot(workspace, "session-1", "user", "console", "before-change");
            assertThat(snapshot.get("commit")).isNotNull();
            Files.writeString(workspace.resolve("notes.txt"), "second version");
            service.snapshot(workspace, "session-1", "user", "console", "after-change");
            assertThat(service.graph(workspace, 10)).hasSize(2)
                .allSatisfy(node -> assertThat(node.get("kind")).isEqualTo("snap"));
            assertThat(service.changedPaths(workspace, String.valueOf(snapshot.get("commit"))))
                .contains("notes.txt");
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
