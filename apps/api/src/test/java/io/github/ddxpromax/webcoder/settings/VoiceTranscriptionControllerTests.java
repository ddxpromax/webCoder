package io.github.ddxpromax.webcoder.settings;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VoiceTranscriptionControllerTests {
    @Test
    void exposesMajoVoiceSettingsAndKeepsProviderIdsIntact() throws Exception {
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        Map<String, String> current = Map.of("audio_mode", "auto",
            "transcription_provider_type", "disabled", "transcription_provider_id", "Provider-A");
        when(settings.getVoice()).thenReturn(current);
        when(settings.setVoice(anyString(), anyString(), anyString())).thenReturn(Map.of(
            "audio_mode", "native", "transcription_provider_type", "whisper_api",
            "transcription_provider_id", "Provider-A"));
        var mvc = MockMvcBuilders.standaloneSetup(new VoiceTranscriptionController(settings)).build();

        mvc.perform(get("/api/workspace/audio-mode")).andExpect(status().isOk())
            .andExpect(jsonPath("$.audio_mode").value("auto"));
        mvc.perform(put("/api/workspace/transcription-provider")
            .contentType(MediaType.APPLICATION_JSON).content("{\"provider_id\":\"Provider-A\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.transcription_provider_id").value("Provider-A"));
        mvc.perform(get("/api/workspace/local-whisper-status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.whisper_installed").value(false));
    }
}
