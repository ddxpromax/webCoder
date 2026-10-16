package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class LastContactStoreTests {
    @Test void persistsTheMostRecentValidContact() throws Exception {
        Path root = Files.createTempDirectory("majo-last-contact-");
        try {
            JsonFileStore files = new JsonFileStore(new ObjectMapper());
            LastContactStore store = new LastContactStore(new MajoWorkingDirectory(root.toString()), files);
            store.record("telegram", "chat-1", "alice");
            store.record("", "chat-2", "ignored");
            LastContactStore reopened = new LastContactStore(new MajoWorkingDirectory(root.toString()), files);
            assertThat(reopened.latest()).containsEntry("channel", "telegram").containsEntry("to", "chat-1").containsEntry("user_id", "alice");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
