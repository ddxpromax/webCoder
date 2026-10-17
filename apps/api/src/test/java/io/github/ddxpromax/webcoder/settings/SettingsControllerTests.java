package io.github.ddxpromax.webcoder.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.HeartbeatScheduler;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SettingsControllerTests {
    @TempDir Path working;

    @Test
    void persistsTheMajoToolOffloadDefaultAction() throws Exception {
        AgentStore agents = new AgentStore(new MajoWorkingDirectory(working.toString()), new JsonFileStore(new ObjectMapper()));
        var mvc = MockMvcBuilders.standaloneSetup(new SettingsController(
            org.mockito.Mockito.mock(SettingsService.class),
            org.mockito.Mockito.mock(HeartbeatScheduler.class), agents
        )).build();
        mvc.perform(get("/api/settings/offload-policy"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.default_action").value("keep_foreground"));
        mvc.perform(put("/api/settings/offload-policy").contentType(MediaType.APPLICATION_JSON).content("{\"default_action\":\"offload\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.default_action").value("offload"));
        mvc.perform(put("/api/settings/offload-policy").contentType(MediaType.APPLICATION_JSON).content("{\"default_action\":\"later\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.default_action").value("later"));
    }
}
