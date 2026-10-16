package io.github.ddxpromax.webcoder.acp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AcpConfigControllerTests {
    @Test void keepsAcpAgentsScopedAndValidatesCustomRuntime() throws Exception {
        Path root = Files.createTempDirectory("majo-acp-");
        try {
            AgentStore store = new AgentStore(new MajoWorkingDirectory(root.toString()), new JsonFileStore(new ObjectMapper()));
            AcpConfigController controller = new AcpConfigController(store);
            MockHttpServletRequest request = new MockHttpServletRequest();
            assertThat(controller.config(request).get("agents")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("codex");
            assertThat(controller.updateEntry("codex", Map.of("tool_parse_mode", "wrong"), request).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.updateEntry("codex", Map.of("tool_parse_mode", "call_detail", "command", "custom"), request).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.updateNodeRuntime(Map.of("node_path", "/not/a/node")).getStatusCode().value()).isEqualTo(400);
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
