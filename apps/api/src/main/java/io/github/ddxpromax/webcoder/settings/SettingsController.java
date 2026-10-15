package io.github.ddxpromax.webcoder.settings;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SettingsController {
    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return service.getSettings();
    }

    @PostMapping("/settings")
    public Map<String, String> saveSettings(
        @RequestBody Map<String, String> body
    ) {
        return service.saveSettings(body);
    }

    @GetMapping("/config/theme")
    public Map<String, Object> getTheme() {
        return service.getTheme();
    }

    @PutMapping("/config/theme")
    public ResponseEntity<?> updateTheme(
        @RequestBody Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(service.setTheme(
                body.get("accent") == null
                    ? ""
                    : String.valueOf(body.get("accent")),
                body.get("accent_dark") == null
                    ? ""
                    : String.valueOf(body.get("accent_dark"))
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(
                Map.of("detail", exception.getMessage())
            );
        }
    }

    @DeleteMapping("/config/theme")
    public Map<String, Object> resetTheme() {
        return service.setTheme("", "");
    }
}
