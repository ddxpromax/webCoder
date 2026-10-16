package io.github.ddxpromax.webcoder.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class ChatsControllerTests {
    @Test void resolvesMajoSessionProjectBeforeAgentProject() throws Exception {
        Path root = Files.createTempDirectory("majo-chat-project-");
        Path session = Files.createDirectory(root.resolve("session"));
        try {
            AgentStore agents = new AgentStore(new MajoWorkingDirectory(root.resolve("majo").toString()), new JsonFileStore(new ObjectMapper()));
            agents.setProjectDir("default", root.toString());
            ChatEntity chat = new ChatEntity(); chat.setId("chat"); chat.setAgentId("default"); chat.setProjectDir(session.toString());
            ChatService service = mock(ChatService.class); when(service.getChat("chat")).thenReturn(chat);
            ChatsController controller = new ChatsController(service, new ObjectMapper(), agents);
            assertThat(controller.projectDir("chat").getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("project_dir", session.toString()).containsEntry("source", "session");
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}
