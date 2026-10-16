package io.github.ddxpromax.webcoder.agent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommandsControllerTests {
    @Test void recognizesAndDispatchesMemoryCommands() throws Exception {
        MemoryCommandService memory=mock(MemoryCommandService.class);when(memory.execute("default"," search cache")).thenReturn("Found memory.");
        var mvc=MockMvcBuilders.standaloneSetup(new CommandsController(memory)).build();
        mvc.perform(post("/api/commands/check").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"/memory search cache\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.is_control_command").value(true)).andExpect(jsonPath("$.command_token").value("/memory"));
        mvc.perform(post("/api/commands/run").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"/memory search cache\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Found memory."));
    }
}
