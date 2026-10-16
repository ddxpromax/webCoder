package io.github.ddxpromax.webcoder.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class PluginServiceTests {
    @Test void installsListsReadsAndUninstallsLocalPlugin() throws Exception {
        Path root = Files.createTempDirectory("majo-plugins-"); Path source = Files.createTempDirectory("source-plugin-");
        try {
            Files.writeString(source.resolve("plugin.json"), "{\"id\":\"sample\",\"name\":\"Sample\",\"version\":\"1.0.0\"}"); Files.writeString(source.resolve("README.md"), "plugin");
            PluginService service = new PluginService(new MajoWorkingDirectory(root.toString()), new ObjectMapper());
            assertThat(service.install(source.toString())).containsEntry("id", "sample");
            assertThat(service.list()).singleElement().extracting(plugin -> plugin.get("name")).isEqualTo("Sample");
            assertThat(service.file(source.getFileName().toString(), "README.md")).isEqualTo("plugin");
            assertThat(service.uninstall(source.getFileName().toString())).isTrue();
        } finally { for (Path directory : java.util.List.of(root, source)) try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
