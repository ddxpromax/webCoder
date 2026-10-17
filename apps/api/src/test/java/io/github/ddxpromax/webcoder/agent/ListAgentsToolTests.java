package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListAgentsToolTests {
    @TempDir Path directory;
    @Test void listsTheDefaultMajoAgent() {
        AgentStore store = new AgentStore(new MajoWorkingDirectory(directory.toString()), new JsonFileStore(new ObjectMapper()));
        assertThat(new ListAgentsTool(store).listAgents()).contains("default", "enabled");
    }
}
