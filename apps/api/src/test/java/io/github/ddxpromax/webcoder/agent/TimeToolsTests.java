package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeToolsTests {
    @TempDir
    Path directory;

    @Test
    void storesTimezoneAndExplicitArgumentOverridesIt() {
        AgentStore agents = new AgentStore(
            new MajoWorkingDirectory(directory.toString()),
            new JsonFileStore(new ObjectMapper())
        );
        TimeTools tools = new TimeTools(agents);
        assertThat(tools.setUserTimezone("Invalid/Zone"))
            .contains("Invalid timezone");
        assertThat(tools.setUserTimezone("Asia/Shanghai"))
            .contains("Asia/Shanghai");
        assertThat(tools.resolveZone(null))
            .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(tools.resolveZone("UTC"))
            .isEqualTo(ZoneId.of("UTC"));
        assertThat(tools.getCurrentTime("UTC"))
            .contains("UTC");
    }
}
