package io.github.ddxpromax.webcoder.settings;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingsLanguageController {
    private static final Set<String> VALID_LANGUAGES = Set.of(
        "en", "zh", "ja", "ru", "pt-BR", "id"
    );
    private static final long UPLOAD_MAX_SIZE_MB = 500L;

    private final Path settingsFile;
    private final JsonFileStore files;

    public SettingsLanguageController(
        MajoWorkingDirectory workingDirectory,
        JsonFileStore files
    ) {
        this.settingsFile = workingDirectory.resolve("settings.json");
        this.files = files;
    }

    @GetMapping("/language")
    public Map<String, Object> getLanguage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "language",
            files.read(settingsFile, Map.of())
                .getOrDefault("language", "en")
        );
        return result;
    }

    @PutMapping("/language")
    public ResponseEntity<?> updateLanguage(
        @RequestBody Map<String, Object> body
    ) {
        String language = String.valueOf(
            body.getOrDefault("language", "")
        ).trim();
        if (!VALID_LANGUAGES.contains(language)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail",
                "Invalid language, must be one of "
                    + VALID_LANGUAGES.stream().sorted().toList()
            ));
        }
        Map<String, Object> data = files.read(settingsFile, Map.of());
        data.put("language", language);
        files.writeAtomic(settingsFile, data);
        return ResponseEntity.ok(Map.of("language", language));
    }

    @GetMapping("/upload-limit")
    public Map<String, Object> getUploadLimit() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload_max_size_mb", UPLOAD_MAX_SIZE_MB);
        return result;
    }

}
