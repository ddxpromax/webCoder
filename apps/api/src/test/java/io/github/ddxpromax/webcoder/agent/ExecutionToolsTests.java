package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionToolsTests {
    @TempDir
    Path workspace;

    @Test
    void shellRunsInWorkspaceAndEnforcesTimeout()
        throws Exception {
        ShellTool shell = new ShellTool(workspace);
        String result = shell.executeCommand(
            "pwd; printf 'ready\\n'", 3
        );
        assertThat(result).startsWith("[success]")
            .contains(workspace.toRealPath().toString())
            .contains("ready");

        long started = System.nanoTime();
        String timeout = shell.executeCommand("sleep 5", 1);
        assertThat(timeout).startsWith("[timeout]");
        assertThat((System.nanoTime() - started)
            / 1_000_000_000.0).isLessThan(3.0);
    }

    @Test
    void gitToolsOperateOnSelectedRepository()
        throws Exception {
        try (Git git = Git.init()
            .setDirectory(workspace.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email",
                "test@example.invalid");
            config.save();
        }
        GitTools tools = new GitTools(workspace);
        Files.writeString(workspace.resolve("README.md"),
            "First\n");
        assertThat(tools.status()).contains("README.md");
        assertThat(tools.add("README.md"))
            .contains("Staged");
        assertThat(tools.commit("Initial commit"))
            .contains("Committed");
        assertThat(tools.log(1)).contains("Initial commit");
        Files.writeString(workspace.resolve("README.md"),
            "Second\n");
        assertThat(tools.diff(false)).contains("Second");
        assertThat(tools.branch(null)).isNotBlank();
    }
}
