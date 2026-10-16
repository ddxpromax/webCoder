package io.github.ddxpromax.webcoder.loop;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoopsControllerTests {
    @Test void savesDuplicatesAndListsCustomModes() throws Exception {Path root=Files.createTempDirectory("majo-loops-");try{AgentStore agents=new AgentStore(new MajoWorkingDirectory(root.toString()),new JsonFileStore(new ObjectMapper()));LoopsController controller=new LoopsController(agents);MockHttpServletRequest request=new MockHttpServletRequest();assertThat(controller.create(Map.of("id","review","name","Review","slash_command","review"),request).getStatusCode().value()).isEqualTo(201);assertThat(controller.customList(request)).hasSize(1);assertThat(controller.duplicate("review",request).getStatusCode().value()).isEqualTo(201);assertThat(controller.list(request)).anyMatch(item->"review".equals(item.get("id")));}finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
}
