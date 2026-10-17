package io.github.ddxpromax.webcoder.plugin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL cache for the two Majo plugin feeds and their synchronization state. */
@Repository
public class PluginCatalogRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PluginCatalogRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<PluginEntry> list(String source) {
        return jdbc.query("""
            SELECT id, source, plugin_id, name, description, version, author, kind,
                   display_size, install_url, sha256, category, display_name, developer,
                   owner, logo_url, downloads, view_count, details_url, is_featured,
                   compat_labels, locales, sort_rank, cached_at
            FROM plugin_cache WHERE source = :source ORDER BY name ASC
            """, Map.of("source", source), PluginCatalogRepository::entry);
    }

    public List<PluginEntry> searchMarket(String search, String category) {
        String normalized = search == null || search.isBlank() ? null : search.toLowerCase();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("search", normalized);
        parameters.put("category", category);
        return jdbc.query("""
            SELECT id, source, plugin_id, name, description, version, author, kind,
                   display_size, install_url, sha256, category, display_name, developer,
                   owner, logo_url, downloads, view_count, details_url, is_featured,
                   compat_labels, locales, sort_rank, cached_at
            FROM plugin_cache
            WHERE source = 'market'
              AND (:search IS NULL OR LOWER(name) LIKE CONCAT('%', :search, '%')
                   OR LOWER(COALESCE(display_name, '')) LIKE CONCAT('%', :search, '%')
                   OR LOWER(COALESCE(description, '')) LIKE CONCAT('%', :search, '%'))
              AND (:category IS NULL OR category = :category)
            ORDER BY name ASC
            """, parameters, PluginCatalogRepository::entry);
    }

    public Optional<SyncConfig> syncConfig(String key) {
        return jdbc.query("""
            SELECT config_key, url, last_synced_at, synced_count, sync_status
            FROM plugin_sync_configs WHERE config_key = :key
            """, Map.of("key", key), PluginCatalogRepository::config).stream().findFirst();
    }

    public List<SyncConfig> syncConfigs() {
        return jdbc.query("""
            SELECT config_key, url, last_synced_at, synced_count, sync_status
            FROM plugin_sync_configs ORDER BY config_key ASC
            """, Map.of(), PluginCatalogRepository::config);
    }

    @Transactional
    public void replace(String source, List<PluginEntry> entries, SyncConfig config) {
        jdbc.update("DELETE FROM plugin_cache WHERE source = :source", Map.of("source", source));
        List<Map<String, ?>> rows = new ArrayList<>();
        for (PluginEntry entry : entries) rows.add(entry.parameters());
        if (!rows.isEmpty()) jdbc.batchUpdate("""
            INSERT INTO plugin_cache (
                id, source, plugin_id, name, description, version, author, kind, display_size,
                install_url, sha256, category, display_name, developer, owner, logo_url,
                downloads, view_count, details_url, is_featured, compat_labels, locales,
                sort_rank, cached_at
            ) VALUES (
                :id, :source, :pluginId, :name, :description, :version, :author, :kind, :displaySize,
                :installUrl, :sha256, :category, :displayName, :developer, :owner, :logoUrl,
                :downloads, :viewCount, :detailsUrl, :featured, :compatLabels, :locales,
                :sortRank, :cachedAt
            )
            """, rows.toArray(Map[]::new));
        saveSyncConfig(config);
    }

    public void saveSyncConfig(SyncConfig config) {
        jdbc.update("""
            INSERT INTO plugin_sync_configs (config_key, url, last_synced_at, synced_count, sync_status)
            VALUES (:key, :url, :lastSyncedAt, :syncedCount, :status)
            ON DUPLICATE KEY UPDATE url = VALUES(url), last_synced_at = VALUES(last_synced_at),
                synced_count = VALUES(synced_count), sync_status = VALUES(sync_status)
            """, config.parameters());
    }

    private static PluginEntry entry(ResultSet result, int ignored) throws SQLException {
        return new PluginEntry(result.getString("id"), result.getString("source"),
            result.getString("plugin_id"), result.getString("name"), result.getString("description"),
            result.getString("version"), result.getString("author"), result.getString("kind"),
            result.getString("display_size"), result.getString("install_url"), result.getString("sha256"),
            result.getString("category"), result.getString("display_name"), result.getString("developer"),
            result.getString("owner"), result.getString("logo_url"), nullableLong(result, "downloads"),
            nullableLong(result, "view_count"), result.getString("details_url"), nullableBoolean(result, "is_featured"),
            result.getString("compat_labels"), result.getString("locales"), nullableInteger(result, "sort_rank"),
            result.getTimestamp("cached_at").toInstant());
    }

    private static SyncConfig config(ResultSet result, int ignored) throws SQLException {
        var timestamp = result.getTimestamp("last_synced_at");
        return new SyncConfig(result.getString("config_key"), result.getString("url"),
            timestamp == null ? null : timestamp.toInstant(), result.getInt("synced_count"), result.getString("sync_status"));
    }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException { long value = rs.getLong(name); return rs.wasNull() ? null : value; }
    private static Integer nullableInteger(ResultSet rs, String name) throws SQLException { int value = rs.getInt(name); return rs.wasNull() ? null : value; }
    private static Boolean nullableBoolean(ResultSet rs, String name) throws SQLException { boolean value = rs.getBoolean(name); return rs.wasNull() ? null : value; }

    public record PluginEntry(String id, String source, String pluginId, String name, String description,
        String version, String author, String kind, String displaySize, String installUrl, String sha256,
        String category, String displayName, String developer, String owner, String logoUrl, Long downloads,
        Long viewCount, String detailsUrl, Boolean featured, String compatLabels, String locales,
        Integer sortRank, Instant cachedAt) {
        Map<String, ?> parameters() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id); values.put("source", source); values.put("pluginId", pluginId);
            values.put("name", nullToEmpty(name)); values.put("description", description); values.put("version", nullToEmpty(version));
            values.put("author", author); values.put("kind", kind); values.put("displaySize", displaySize); values.put("installUrl", installUrl);
            values.put("sha256", sha256); values.put("category", category); values.put("displayName", displayName); values.put("developer", developer);
            values.put("owner", owner); values.put("logoUrl", logoUrl); values.put("downloads", downloads); values.put("viewCount", viewCount);
            values.put("detailsUrl", detailsUrl); values.put("featured", featured); values.put("compatLabels", compatLabels); values.put("locales", locales);
            values.put("sortRank", sortRank); values.put("cachedAt", cachedAt);
            return values;
        }
    }
    public record SyncConfig(String key, String url, Instant lastSyncedAt, int syncedCount, String status) {
        Map<String, ?> parameters() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("key", key); values.put("url", url); values.put("lastSyncedAt", lastSyncedAt);
            values.put("syncedCount", syncedCount); values.put("status", status); return values;
        }
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
