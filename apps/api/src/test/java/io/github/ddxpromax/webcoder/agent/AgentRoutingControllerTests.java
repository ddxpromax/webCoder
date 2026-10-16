package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRoutingControllerTests {
    @Test void persistsMajoAgentScopedRouting() throws Exception {
        Path root = Files.createTempDirectory("majo-routing-");
        try {
            AgentStore agents = new AgentStore(new MajoWorkingDirectory(root.toString()), new JsonFileStore(new ObjectMapper()));
            AgentRoutingController controller = new AgentRoutingController(agents);
            assertThat(controller.update("default", Map.of("strategy", "fallback")).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.get("default").getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("strategy", "fallback");
            assertThat(controller.get("missing").getStatusCode().value()).isEqualTo(404);
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
