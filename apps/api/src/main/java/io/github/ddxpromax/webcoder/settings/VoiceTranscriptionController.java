package io.github.ddxpromax.webcoder.settings;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo's persisted voice-capture and transcription-provider settings API. */
@RestController
@RequestMapping("/api/workspace")
public class VoiceTranscriptionController {
    private final SettingsService settings;

    public VoiceTranscriptionController(SettingsService settings) { this.settings = settings; }

    @GetMapping("/audio-mode") public Map<String, String> audioMode() {
        return Map.of("audio_mode", settings.getVoice().get("audio_mode"));
    }

    @PutMapping("/audio-mode") public ResponseEntity<?> setAudioMode(@RequestBody Map<String, Object> body) {
        return update(body, settings.getVoice().get("transcription_provider_type"), settings.getVoice().get("transcription_provider_id"));
    }

    @GetMapping("/transcription-provider-type") public Map<String, String> providerType() {
        return Map.of("transcription_provider_type", settings.getVoice().get("transcription_provider_type"));
    }

    @PutMapping("/transcription-provider-type") public ResponseEntity<?> setProviderType(@RequestBody Map<String, Object> body) {
        return update(Map.of("audio_mode", settings.getVoice().get("audio_mode")), text(body.get("transcription_provider_type")), settings.getVoice().get("transcription_provider_id"));
    }

    @GetMapping("/transcription-provider") public Map<String, String> provider() {
        return Map.of("provider_id", settings.getVoice().get("transcription_provider_id"));
    }

    @PutMapping("/transcription-provider") public ResponseEntity<?> setProvider(@RequestBody Map<String, Object> body) {
        return update(Map.of("audio_mode", settings.getVoice().get("audio_mode")), settings.getVoice().get("transcription_provider_type"), text(body.get("provider_id")));
    }

    @GetMapping("/transcription-providers") public Map<String, Object> providers() {
        Map<String, String> voice = settings.getVoice();
        return Map.of("providers", List.of(), "configured_provider_id", voice.get("transcription_provider_id"));
    }

    @GetMapping("/local-whisper-status") public Map<String, Object> localWhisperStatus() {
        return Map.of("available", false, "ffmpeg_installed", false, "whisper_installed", false);
    }

    @PostMapping("/transcribe") public Map<String, String> transcribe() { return Map.of("text", ""); }

    private ResponseEntity<?> update(Object audioMode, String providerType, String providerId) {
        String mode = audioMode instanceof Map<?, ?> map ? text(map.get("audio_mode")) : text(audioMode);
        try { return ResponseEntity.ok(settings.setVoice(mode, providerType, providerId)); }
        catch (IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage())); }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
