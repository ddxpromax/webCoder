package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentStatusControllerTests {
    @Test void returnsIdleAndDisabledStates() throws Exception {Path root=Files.createTempDirectory("majo-status-");try{AgentStore agents=new AgentStore(new MajoWorkingDirectory(root.toString()),new JsonFileStore(new ObjectMapper()));TaskTracker tasks=new TaskTracker();AgentStatusController controller=new AgentStatusController(agents,tasks);assertThat(((Map<?,?>)controller.status("default").getBody()).get("status")).isEqualTo("idle");agents.updateAgent("default",Map.of("enabled",false));assertThat(((Map<?,?>)controller.status("default").getBody()).get("status")).isEqualTo("disabled");assertThat(controller.status("missing").getStatusCode().value()).isEqualTo(404);}finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
}
