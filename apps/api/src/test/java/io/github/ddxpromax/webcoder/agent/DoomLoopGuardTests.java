package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoomLoopGuardTests {
    @TempDir Path directory;
    @Test void warnsThenStopsOnlyConsecutiveIdenticalCalls() {
        AgentStore store = new AgentStore(new MajoWorkingDirectory(directory.toString()), new JsonFileStore(new ObjectMapper()));
        DoomLoopGuard guard = new DoomLoopGuard(store);
        assertThat(guard.check("s", "read_file", Map.of("path", "a"))).isNull();
        assertThat(guard.check("s", "read_file", Map.of("path", "a"))).isNull();
        assertThat(guard.check("s", "read_file", Map.of("path", "a"))).contains("blocked");
        assertThat(guard.check("s", "read_file", Map.of("path", "b"))).isNull();
        for (int ignored = 0; ignored < 5; ignored++) guard.check("s2", "read_file", Map.of("path", "a"));
        assertThat(guard.check("s2", "read_file", Map.of("path", "a"))).contains("stopped");
    }
}
