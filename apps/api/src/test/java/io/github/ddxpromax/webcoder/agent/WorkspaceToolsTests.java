package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolsTests {
    @TempDir
    Path workspace;

    @Test
    void fileToolsSearchAndEditOnlyWithinOneWorkspace()
        throws Exception {
        WorkspaceTools tools = new WorkspaceTools(workspace);
        assertThat(tools.writeFile("src/Main.java",
            "class Main {}\n"))
            .contains("[created]");
        assertThat(tools.readFile("src/Main.java", 0, 1))
            .contains("1| class Main {}");
        assertThat(tools.listDirectory("src"))
            .contains("Main.java");
        assertThat(tools.searchCode("Main", "*.java", 10))
            .contains("src/Main.java:1");
        assertThat(tools.findSymbol("Main", ".java"))
            .contains("src/Main.java:1");
        assertThat(tools.editFile("src/Main.java", "Main", "App"))
            .contains("[edited]");
        assertThat(tools.appendFile("src/Main.java", "// end"))
            .contains("[appended]");
        assertThat(Files.readString(
            workspace.resolve("src/Main.java")
        )).isEqualTo("class App {}\n// end");

        assertThatThrownBy(() -> tools.readFile(
            "../outside.txt", null, null
        )).isInstanceOf(IllegalArgumentException.class);
        Path outside = workspace.getParent().resolve(
            "outside-" + workspace.getFileName()
        );
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(
                workspace.resolve("linked.txt"), outside
            );
            assertThatThrownBy(() -> tools.writeFile(
                "linked.txt", "changed"
            )).isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(outside)).isEqualTo("secret");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void agentScopeToolkitRegistersMajoFileToolNames() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkspaceTools(workspace));
        Set<String> names = toolkit.getToolSchemas().stream()
            .map(schema -> schema.getName())
            .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
            "read_file", "write_file", "edit_file",
            "append_file", "list_directory", "search_code", "find_symbol"
        );
    }
}
