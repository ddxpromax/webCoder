package io.github.ddxpromax.webcoder.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DebugLogControllerTests {
    @TempDir Path directory;

    @Test
    void returnsOnlyTheRequestedTailOfTheFixedDaemonLog() throws Exception {
        Path log = directory.resolve("majo.log");
        Files.writeString(log, "INFO first\nWARN second\nERROR third\n");
        var mvc = MockMvcBuilders.standaloneSetup(new DebugLogController(log)).build();
        mvc.perform(get("/api/console/debug/backend-logs").param("lines", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.lines").value(20))
            .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("ERROR third")));
    }
}
