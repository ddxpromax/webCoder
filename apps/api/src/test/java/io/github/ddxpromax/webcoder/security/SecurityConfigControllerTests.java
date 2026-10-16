package io.github.ddxpromax.webcoder.security;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import io.github.ddxpromax.webcoder.agent.ToolGuardService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityConfigControllerTests {
    @Test void persistsPoliciesAndPreservesMajoDefaults() throws Exception {
        Path root=Files.createTempDirectory("majo-security-");
        try { AgentStore agents=new AgentStore(new MajoWorkingDirectory(root.toString()),new JsonFileStore(new ObjectMapper()));SecurityConfigController controller=new SecurityConfigController(agents,new ToolGuardService(agents));
            assertThat(controller.toolGuard()).containsEntry("enabled",true).containsKey("shell_evasion_checks");
            controller.putFileGuard(Map.of("enabled",true,"paths",java.util.List.of(".env")));
            assertThat(controller.fileGuard()).containsEntry("paths",java.util.List.of(".env"));
            assertThat(controller.whitelist(Map.of("skill_name","safe-skill")).get("whitelisted")).isEqualTo(true);
            assertThat(controller.scanner().get("whitelist")).isInstanceOf(java.util.List.class);
        } finally {try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
