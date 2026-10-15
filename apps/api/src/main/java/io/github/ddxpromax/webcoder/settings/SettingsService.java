package io.github.ddxpromax.webcoder.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private final SettingsRepository repository;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    public Map<String, String> getSettings() {
        var row = repository.find();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("apiKey", row.apiKey());
        result.put("baseUrl", row.baseUrl());
        result.put("modelName", row.modelName());
        result.put("workspace", row.workspace());
        return result;
    }

    @Transactional
    public Map<String, String> saveSettings(Map<String, String> body) {
        repository.updateCore(body);
        return getSettings();
    }

    public Map<String, Object> getTheme() {
        var row = repository.find();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "accent",
            row.themeAccent() == null ? "" : row.themeAccent()
        );
        result.put(
            "accent_dark",
            row.themeAccentDark() == null
                ? ""
                : row.themeAccentDark()
        );
        return result;
    }

    @Transactional
    public Map<String, Object> setTheme(
        String accent,
        String accentDark
    ) {
        String normalizedAccent = normalizeColor(accent);
        String normalizedDark = normalizeColor(accentDark);
        repository.updateTheme(normalizedAccent, normalizedDark);
        return getTheme();
    }

    private static String normalizeColor(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.matches("^#[0-9a-fA-F]{6}$")) {
            throw new IllegalArgumentException(
                "accent must be #rrggbb, got: " + trimmed
            );
        }
        return trimmed.toLowerCase();
    }
}
