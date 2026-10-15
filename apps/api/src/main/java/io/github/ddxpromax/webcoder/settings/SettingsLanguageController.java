package io.github.ddxpromax.webcoder.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
    private final ObjectMapper mapper;

    public SettingsLanguageController(
        @Value("${majo.working-dir:}") String configuredDir,
        ObjectMapper mapper
    ) {
        String environmentDir = System.getenv("MAJO_WORKING_DIR");
        Path workingDir;
        if (configuredDir != null && !configuredDir.isBlank()) {
            workingDir = Path.of(configuredDir);
        } else if (environmentDir != null && !environmentDir.isBlank()) {
            workingDir = Path.of(environmentDir);
        } else {
            workingDir = Path.of(System.getProperty("user.dir"))
                .resolve("data")
                .resolve("majo");
        }
        this.settingsFile = workingDir.toAbsolutePath()
            .normalize()
            .resolve("settings.json");
        this.mapper = mapper;
    }

    @GetMapping("/language")
    public Map<String, Object> getLanguage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("language", load().getOrDefault("language", "en"));
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
        Map<String, Object> data = load();
        data.put("language", language);
        save(data);
        return ResponseEntity.ok(Map.of("language", language));
    }

    @GetMapping("/upload-limit")
    public Map<String, Object> getUploadLimit() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload_max_size_mb", UPLOAD_MAX_SIZE_MB);
        return result;
    }

    private Map<String, Object> load() {
        if (!Files.isRegularFile(settingsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> data = mapper.readValue(
                settingsFile.toFile(),
                new TypeReference<>() {}
            );
            return data == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(data);
        } catch (IOException exception) {
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<String, Object> data) {
        Path temporary = null;
        try {
            Files.createDirectories(settingsFile.getParent());
            temporary = Files.createTempFile(
                settingsFile.getParent(), "settings-", ".tmp"
            );
            mapper.writeValue(temporary.toFile(), data);
            try {
                Files.move(
                    temporary,
                    settingsFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                    temporary,
                    settingsFile,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to save language settings", exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The published settings file is already intact.
                }
            }
        }
    }
}
