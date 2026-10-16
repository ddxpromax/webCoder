package io.github.ddxpromax.webcoder.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import io.github.ddxpromax.webcoder.skill.SkillWorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketControllerTests {
    @Test void searchesSkillsAcrossMajoWorkspaces() throws Exception {
        Path root = Files.createTempDirectory("majo-market-");
        try {
            AgentStore agents = new AgentStore(new MajoWorkingDirectory(root.toString()), new JsonFileStore(new ObjectMapper()));
            SkillWorkspaceService skills = new SkillWorkspaceService(new JsonFileStore(new ObjectMapper()));
            skills.create(agents.defaultWorkspaceDir(), "review", "---\nname: review\ndescription: Review source code\n---\nUse this skill.", true);
            MarketController controller = new MarketController(agents, skills);
            assertThat(controller.providers()).singleElement().extracting(row -> row.get("key")).isEqualTo("majo-pool");
            assertThat(controller.search(Map.of("query", "review")).get("results"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extracting(row -> row.get("slug")).isEqualTo("review");
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
