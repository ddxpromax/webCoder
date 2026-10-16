package io.github.ddxpromax.webcoder.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessControllerTests {
    @Test
    void exposesMajoProviderCatalogAndPersistsLocalLoginMarker() throws Exception {
        Path root = Files.createTempDirectory("majo-harness-");
        Path binary = Files.createTempFile("majo-codex-", ".bin");
        try {
            binary.toFile().setExecutable(true);
            HarnessService service = new HarnessService(
                new MajoWorkingDirectory(root.toString()),
                new JsonFileStore(new ObjectMapper())
            );
            HarnessController controller = new HarnessController(service);

            assertThat(controller.list().get("providers"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(3);
            assertThat(controller.models("codex").getStatusCode().value())
                .isEqualTo(200);
            assertThat(controller.status("codex", Map.of("settings",
                Map.of("binary", binary.toString()))).getBody())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("installed", true)
                .containsEntry("authenticated", false);

            assertThat(controller.login("codex", Map.of("device_code", true,
                "settings", Map.of("binary", binary.toString())))
                .getStatusCode().value()).isEqualTo(200);
            assertThat(controller.status("codex", Map.of("settings",
                Map.of("binary", binary.toString()))).getBody())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("authenticated", true);
            assertThat(controller.logout("codex").getStatusCode().value())
                .isEqualTo(200);
            assertThat(controller.models("unknown").getStatusCode().value())
                .isEqualTo(404);
            assertThat(controller.login("claude", Map.of()).getStatusCode().value())
                .isEqualTo(409);
        } finally {
            Files.deleteIfExists(binary);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
