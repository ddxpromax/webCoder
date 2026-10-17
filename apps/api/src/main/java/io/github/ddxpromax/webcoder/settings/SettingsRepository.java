package io.github.ddxpromax.webcoder.settings;

import java.util.Map;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public SettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SettingsRow find() {
        return jdbc.queryForObject(
            """
            SELECT api_key, base_url, model_name, workspace,
                   theme_accent, theme_accent_dark, heartbeat_enabled, heartbeat_every,
                   heartbeat_target, heartbeat_timeout_seconds, heartbeat_active_hours_start,
                   heartbeat_active_hours_end, audio_mode, transcription_provider_type,
                   transcription_provider_id
            FROM settings
            WHERE id = 1
            """,
            Map.of(),
            (rs, rowNum) -> new SettingsRow(
                rs.getString("api_key"),
                rs.getString("base_url"),
                rs.getString("model_name"),
                rs.getString("workspace"),
                rs.getString("theme_accent"),
                rs.getString("theme_accent_dark"), rs.getBoolean("heartbeat_enabled"),
                rs.getString("heartbeat_every"), rs.getString("heartbeat_target"),
                rs.getInt("heartbeat_timeout_seconds"), rs.getString("heartbeat_active_hours_start"),
                rs.getString("heartbeat_active_hours_end"), rs.getString("audio_mode"),
                rs.getString("transcription_provider_type"), rs.getString("transcription_provider_id")
            )
        );
    }

    public void updateCore(Map<String, String> body) {
        StringJoiner assignments = new StringJoiner(", ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        addIfPresent(body, "apiKey", "api_key", assignments, params);
        addIfPresent(body, "baseUrl", "base_url", assignments, params);
        addIfPresent(body, "modelName", "model_name", assignments, params);
        addIfPresent(body, "workspace", "workspace", assignments, params);
        if (assignments.length() == 0) {
            return;
        }
        jdbc.update(
            "UPDATE settings SET " + assignments + " WHERE id = 1",
            params
        );
    }

    public void updateTheme(String accent, String accentDark) {
        jdbc.update(
            """
            UPDATE settings
            SET theme_accent = :accent,
                theme_accent_dark = :accentDark
            WHERE id = 1
            """,
            new MapSqlParameterSource()
                .addValue("accent", accent)
                .addValue("accentDark", accentDark)
        );
    }

    public void updateHeartbeat(boolean enabled, String every, String target, int timeout, String start, String end) {
        jdbc.update("""
            UPDATE settings SET heartbeat_enabled=:enabled, heartbeat_every=:every,
              heartbeat_target=:target, heartbeat_timeout_seconds=:timeout,
              heartbeat_active_hours_start=:start, heartbeat_active_hours_end=:end WHERE id=1
            """,
            new MapSqlParameterSource().addValue("enabled", enabled).addValue("every", every)
                .addValue("target", target).addValue("timeout", timeout).addValue("start", start).addValue("end", end));
    }

    public void updateVoice(String audioMode, String providerType, String providerId) {
        jdbc.update("""
            UPDATE settings SET audio_mode=:audioMode,
              transcription_provider_type=:providerType,
              transcription_provider_id=:providerId WHERE id=1
            """, new MapSqlParameterSource().addValue("audioMode", audioMode)
                .addValue("providerType", providerType).addValue("providerId", providerId));
    }

    private static void addIfPresent(
        Map<String, String> body,
        String requestKey,
        String column,
        StringJoiner assignments,
        MapSqlParameterSource params
    ) {
        if (body.containsKey(requestKey)) {
            assignments.add(column + " = :" + requestKey);
            params.addValue(requestKey, body.get(requestKey));
        }
    }

    public record SettingsRow(
        String apiKey,
        String baseUrl,
        String modelName,
        String workspace,
        String themeAccent,
        String themeAccentDark, boolean heartbeatEnabled, String heartbeatEvery,
        String heartbeatTarget, int heartbeatTimeoutSeconds, String heartbeatActiveHoursStart,
        String heartbeatActiveHoursEnd, String audioMode, String transcriptionProviderType,
        String transcriptionProviderId
    ) {}
}
