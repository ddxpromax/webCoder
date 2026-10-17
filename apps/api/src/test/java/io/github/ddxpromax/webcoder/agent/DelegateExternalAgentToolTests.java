package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelegateExternalAgentToolTests {
    @TempDir Path directory;

    @Test
    void parsesUtf8ContentLengthFramesAtTheByteLevel() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"text\":\"café\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String framed = "Content-Length: " + bytes.length + "\r\n\r\n" + body;

        assertThat(DelegateExternalAgentTool.readFrame(new ByteArrayInputStream(
            framed.getBytes(StandardCharsets.UTF_8)
        )).path("result").path("text").asText()).isEqualTo("café");
    }

    @Test
    void rejectsDisabledOrMissingRunnersBeforeStartingAProcess() {
        AgentStore store = new AgentStore(
            new MajoWorkingDirectory(directory.toString()),
            new JsonFileStore(new ObjectMapper())
        );
        store.updateAgent("default", Map.of("acp", Map.of("agents", Map.of(
            "local", Map.of("enabled", false, "command", "never-run", "args", List.of())
        ))));
        DelegateExternalAgentTool tool = new DelegateExternalAgentTool(
            "default", directory, store, new SubagentTaskRegistry()
        );

        assertThat(tool.delegateExternalAgent("inspect this", "local", false))
            .isEqualTo("ACP agent 'local' is not configured or is disabled.");
        assertThat(tool.delegateExternalAgent("", "opencode", false))
            .isEqualTo("task must not be empty");
    }
}
