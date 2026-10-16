package io.github.ddxpromax.webcoder.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalModelsControllerTests {
    @Test void discoversOnlySupportedLocalModelFiles() throws Exception {
        Path root=Files.createTempDirectory("majo-models-");
        try {
            Path directory=root.resolve("local-models"); Files.createDirectories(directory);
            Files.writeString(directory.resolve("coder.gguf"),"model"); Files.writeString(directory.resolve("notes.txt"),"ignore");
            LocalModelsController controller=new LocalModelsController(new MajoWorkingDirectory(root.toString()));
            assertThat(controller.models()).singleElement().extracting(item->item.get("id")).isEqualTo("coder.gguf");
            assertThat(controller.delete("../agents.json").get("status")).isEqualTo("error");
        } finally { try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);} }
    }
}
