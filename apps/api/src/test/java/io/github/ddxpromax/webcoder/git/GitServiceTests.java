package io.github.ddxpromax.webcoder.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ddxpromax.webcoder.agent.LocalWorkspaceMutationGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitServiceTests {
    @TempDir Path workspace;

    @Test
    void initializesAndOperatesOnAnIsolatedMajoWorkspaceRepository() throws Exception {
        GitService git = new GitService(LocalWorkspaceMutationGate.shared());
        assertThat(git.status(workspace).get("branch")).isEqualTo("master");
        assertThat(workspace.resolve(".gitignore")).isRegularFile();

        Files.writeString(workspace.resolve("draft.txt"), "first\n");
        assertThat(git.status(workspace).get("changes").toString()).contains("draft.txt");
        assertThat(git.diff(workspace, "draft.txt", false, true).get("diff").toString())
            .contains("+first");
        assertThat(git.stage(workspace, List.of("draft.txt")).get("staged"))
            .isEqualTo(List.of("draft.txt"));
        String commit = String.valueOf(git.commit(workspace, "Add draft").get("output"));
        assertThat(git.log(workspace, 1)).singleElement().extracting(row -> row.get("message"))
            .isEqualTo("Add draft");
        assertThat(git.commitDiff(workspace, commit).get("diff").toString())
            .contains("draft.txt");

        assertThat(git.checkout(workspace, "review", true).get("branch")).isEqualTo("review");
        assertThat(git.branches(workspace)).anyMatch(row -> Boolean.TRUE.equals(row.get("current"))
            && "review".equals(row.get("name")));
        Files.writeString(workspace.resolve("draft.txt"), "second\n");
        assertThat(git.discard(workspace, List.of("draft.txt")).get("discarded"))
            .isEqualTo(List.of("draft.txt"));
        assertThat(Files.readString(workspace.resolve("draft.txt"))).isEqualTo("first\n");
    }
}
