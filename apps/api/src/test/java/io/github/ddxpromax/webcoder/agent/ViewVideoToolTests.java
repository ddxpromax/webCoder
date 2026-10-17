package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewVideoToolTests {
    @TempDir Path workspace;
    @Test void rejectsUnsupportedFileTypesBeforeTryingFfmpeg() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "not video");
        assertThat(new ViewVideoTool(workspace).viewVideo("notes.txt", null).getOutput().getFirst().toString()).contains("Unsupported video format");
    }
}
