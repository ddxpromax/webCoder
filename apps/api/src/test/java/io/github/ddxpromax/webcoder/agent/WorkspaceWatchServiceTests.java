package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWatchServiceTests {
    @TempDir
    Path workspace;

    @Test
    void reportsAddedModifiedAndDeletedFilesWithNormalizedPaths()
        throws Exception {
        Path source = workspace.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}");
        Map<String, WorkspaceWatchService.FileStamp> first =
            WorkspaceWatchService.snapshot(workspace, name -> name.startsWith("."));

        Files.writeString(source, "class App { int version; }");
        Files.writeString(workspace.resolve("README.md"), "Hello");
        Map<String, WorkspaceWatchService.FileStamp> second =
            WorkspaceWatchService.snapshot(workspace, name -> name.startsWith("."));
        assertThat(WorkspaceWatchService.changes(first, second)).containsExactly(
            new WorkspaceWatchService.FileChange("added", "README.md"),
            new WorkspaceWatchService.FileChange("modified", "src/App.java")
        );

        Files.delete(source);
        Map<String, WorkspaceWatchService.FileStamp> third =
            WorkspaceWatchService.snapshot(workspace, name -> name.startsWith("."));
        assertThat(WorkspaceWatchService.changes(second, third)).containsExactly(
            new WorkspaceWatchService.FileChange("deleted", "src/App.java")
        );
    }

    @Test
    void excludesSkippedAndSymlinkedFiles() throws Exception {
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "private");
        Path outside = workspace.resolveSibling("outside.txt");
        Files.writeString(outside, "outside");
        Files.createSymbolicLink(workspace.resolve("linked.txt"), outside);

        assertThat(WorkspaceWatchService.snapshot(
            workspace, name -> name.startsWith(".")
        )).isEmpty();
    }
}
