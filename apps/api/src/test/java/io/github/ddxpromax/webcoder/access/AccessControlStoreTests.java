package io.github.ddxpromax.webcoder.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccessControlStoreTests {
    @Test
    void movesUsersBetweenPendingWhitelistAndBlacklistAndPersists() throws Exception {
        Path root = Files.createTempDirectory("majo-acl-");
        try {
            JsonFileStore files = new JsonFileStore(new ObjectMapper());
            MajoWorkingDirectory working = new MajoWorkingDirectory(root.toString());
            AccessControlStore store = new AccessControlStore(working, files);
            store.addPending("telegram", "42", "Please let me in", "Ada");
            assertThat(store.allPending()).singleElement().extracting(item -> item.get("username")).isEqualTo("Ada");
            assertThat(store.approvePending("telegram", "42", "trusted")).isTrue();
            assertThat(store.isWhitelisted("telegram", "42")).isTrue();
            assertThat(store.allPending()).isEmpty();

            store.addToBlacklist("telegram", "42", "blocked", "Ada");
            assertThat(store.isWhitelisted("telegram", "42")).isFalse();
            assertThat(store.isBlacklisted("telegram", "42")).isTrue();
            assertThat(new AccessControlStore(working, files).isBlacklisted("telegram", "42")).isTrue();
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
