package io.github.ddxpromax.webcoder.channel;

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

class ChannelsConfigControllerTests {
    @Test void persistsMajoChannelConfigAndExposesSchemas() throws Exception { Path root = Files.createTempDirectory("majo-channels-"); try { ChannelsConfigController controller = new ChannelsConfigController(new AgentStore(new MajoWorkingDirectory(root.toString()), new JsonFileStore(new ObjectMapper()))); assertThat(controller.types()).contains("console", "telegram"); assertThat(controller.get("console").getStatusCode().value()).isEqualTo(200); assertThat(controller.update("telegram", Map.of("enabled", true, "bot_token", "configured")).getStatusCode().value()).isEqualTo(200); assertThat(controller.get("telegram").getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("enabled", true); assertThat(controller.qrcode("telegram").getStatusCode().value()).isEqualTo(501); } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } } }
}
