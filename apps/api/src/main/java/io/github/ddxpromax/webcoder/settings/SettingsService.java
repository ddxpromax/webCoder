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

    public Map<String, Object> getHeartbeat() {
        var row = repository.find(); Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", row.heartbeatEnabled()); value.put("every", row.heartbeatEvery());
        value.put("target", row.heartbeatTarget()); value.put("timeoutSeconds", row.heartbeatTimeoutSeconds());
        value.put("active_hours", Map.of("start", row.heartbeatActiveHoursStart() == null ? "" : row.heartbeatActiveHoursStart(), "end", row.heartbeatActiveHoursEnd() == null ? "" : row.heartbeatActiveHoursEnd()));
        return value;
    }

    @Transactional
    public void setHeartbeat(boolean enabled, String every, String target, int timeout, String start, String end) {
        if (every == null || every.isBlank()) throw new IllegalArgumentException("heartbeat interval is required");
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600");
        repository.updateHeartbeat(enabled, every.strip(), target == null || target.isBlank() ? "main" : target.strip(), timeout, normalizeTime(start), normalizeTime(end));
    }

    private static String normalizeTime(String value) { if (value == null || value.isBlank()) return null; if (!value.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new IllegalArgumentException("active hours must use HH:mm"); return value; }

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
