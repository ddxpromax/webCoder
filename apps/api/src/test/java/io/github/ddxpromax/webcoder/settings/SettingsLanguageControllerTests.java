package io.github.ddxpromax.webcoder.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SettingsLanguageControllerTests {
    @TempDir
    Path workingDir;

    @Test
    void languagePersistsInMajoSettingsJsonAndUploadLimitMatches()
        throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(
            new SettingsLanguageController(
                workingDir.toString(), mapper
            )
        ).build();

        mvc.perform(get("/api/settings/language"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("en"));

        mvc.perform(put("/api/settings/language")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"pt-BR\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("pt-BR"));

        Path file = workingDir.resolve("settings.json");
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(mapper.readTree(file.toFile())
            .path("language").asText()).isEqualTo("pt-BR");

        mvc.perform(put("/api/settings/language")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"invalid\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/settings/language"))
            .andExpect(jsonPath("$.language").value("pt-BR"));

        mvc.perform(get("/api/settings/upload-limit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.upload_max_size_mb")
                .value(500));
    }
}
