package io.github.ddxpromax.webcoder.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Implements Majo's official catalog and community market synchronization.
 * Feed data is cached in MySQL so browsing remains available while an upstream
 * endpoint is down; only an explicit sync performs network I/O.
 */
@Service
public class PluginCatalogService {
    static final String CATALOG = "catalog";
    static final String MARKET = "market";
    static final String DEFAULT_CATALOG_URL = "https://download.qwenpaw.agentscope.io/metadata/index.json";
    static final String DEFAULT_MARKET_URL = "https://platform.agentscope.io/openapi/v1/plugins";

    private final PluginCatalogRepository cache;
    private final PluginService plugins;
    private final ObjectMapper mapper;
    private final Function<String, String> fetch;
    private final Clock clock;

    public PluginCatalogService(PluginCatalogRepository cache, PluginService plugins, ObjectMapper mapper) {
        this(cache, plugins, mapper, new HttpFetcher(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build())::get, Clock.systemUTC());
    }

    PluginCatalogService(PluginCatalogRepository cache, PluginService plugins, ObjectMapper mapper,
                         Function<String, String> fetch, Clock clock) {
        this.cache = cache; this.plugins = plugins; this.mapper = mapper; this.fetch = fetch; this.clock = clock;
    }

    public Map<String, Object> catalog() {
        List<PluginCatalogRepository.PluginEntry> entries = cache.list(CATALOG);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_at", entries.isEmpty() ? null : entries.get(0).cachedAt().toString());
        result.put("error", null);
        Map<String, String> installedVersions = installedVersions();
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (var entry : entries) {
            String installedVersion = installedVersions.get(entry.pluginId());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", entry.id()); value.put("plugin_id", entry.pluginId()); value.put("name", entry.name());
            value.put("description", entry.description()); value.put("version", entry.version()); value.put("author", entry.author());
            value.put("kind", entry.kind()); value.put("size", entry.displaySize()); value.put("sha256", entry.sha256());
            value.put("install_url", entry.installUrl()); value.put("installed", installedVersion != null);
            value.put("installed_version", installedVersion); value.put("upgrade_available", isUpgradeAvailable(installedVersion, entry.version()));
            mapped.add(value);
        }
        mapped.sort(Comparator.comparing((Map<String, Object> e) -> text(e.get("kind"))).thenComparing(e -> text(e.get("name"))));
        result.put("plugins", mapped);
        return result;
    }

    public Map<String, Object> searchMarket(int pageNumber, int pageSize, String search, String category, String sortBy) {
        String normalizedCategory = mapMarketCategory(category);
        List<PluginCatalogRepository.PluginEntry> entries = category != null && !category.isBlank() && normalizedCategory == null
            ? List.of() : new ArrayList<>(cache.searchMarket(search, normalizedCategory));
        Comparator<PluginCatalogRepository.PluginEntry> order = switch (sortBy == null ? "downloads" : sortBy) {
            case "downloads" -> Comparator.comparing((PluginCatalogRepository.PluginEntry e) -> e.downloads() == null ? Long.MIN_VALUE : e.downloads()).reversed();
            case "fauvarate" -> Comparator.comparing((PluginCatalogRepository.PluginEntry e) -> e.viewCount() == null ? Long.MIN_VALUE : e.viewCount()).reversed();
            default -> Comparator.comparing(e -> e.sortRank() == null ? Integer.MAX_VALUE : e.sortRank());
        };
        entries.sort(order.thenComparing(PluginCatalogRepository.PluginEntry::name));
        int safePage = Math.max(1, pageNumber), safeSize = Math.max(1, Math.min(100, pageSize));
        int from = Math.min(entries.size(), (safePage - 1) * safeSize), to = Math.min(entries.size(), from + safeSize);
        List<Map<String, Object>> page = entries.subList(from, to).stream().map(this::marketEntry).toList();
        return Map.of("success", true, "message", "", "data", Map.of("total", entries.size(), "plugins", page));
    }

    public List<Map<String, Object>> syncConfigs() {
        Map<String, PluginCatalogRepository.SyncConfig> existing = new LinkedHashMap<>();
        for (var value : cache.syncConfigs()) existing.put(value.key(), value);
        return List.of(configMap(existing.getOrDefault(CATALOG, defaultConfig(CATALOG))),
            configMap(existing.getOrDefault(MARKET, defaultConfig(MARKET))));
    }

    public Map<String, Object> updateSyncConfig(String key, String url) {
        if (!CATALOG.equals(key) && !MARKET.equals(key)) throw new IllegalArgumentException("Unknown sync configuration: " + key);
        validateUrl(url);
        PluginCatalogRepository.SyncConfig previous = cache.syncConfig(key).orElse(defaultConfig(key));
        PluginCatalogRepository.SyncConfig updated = new PluginCatalogRepository.SyncConfig(key, url.trim(), previous.lastSyncedAt(), previous.syncedCount(), "pending");
        cache.saveSyncConfig(updated);
        return configMap(updated);
    }

    public Map<String, Object> sync(String key) {
        PluginCatalogRepository.SyncConfig config = cache.syncConfig(key).orElse(defaultConfig(key));
        try {
            List<PluginCatalogRepository.PluginEntry> entries = CATALOG.equals(key) ? fetchCatalog(config.url())
                : MARKET.equals(key) ? fetchMarket(config.url()) : throwUnknown(key);
            PluginCatalogRepository.SyncConfig completed = new PluginCatalogRepository.SyncConfig(key, config.url(), Instant.now(clock), entries.size(), "success");
            cache.replace(key, entries, completed);
            return Map.of("synced", entries.size());
        } catch (RuntimeException exception) {
            cache.saveSyncConfig(new PluginCatalogRepository.SyncConfig(key, config.url(), config.lastSyncedAt(), config.syncedCount(), "failed"));
            throw exception;
        }
    }

    private List<PluginCatalogRepository.PluginEntry> fetchCatalog(String configuredUrl) {
        Map<String, Object> main = object(readJson(configuredUrl, "main catalog"));
        Map<String, Object> products = object(main.get("products"));
        String indexPath = text(object(products.get("plugins")).get("index_url"));
        if (indexPath.isBlank()) throw new CatalogSyncException("The main catalog does not define a plugin index.");
        URI indexUri = resolveFromOrigin(configuredUrl, indexPath);
        Map<String, Object> index = object(readJson(indexUri.toString(), "plugin index"));
        Object files = index.get("files");
        if (!(files instanceof Map<?, ?> rawFiles)) return List.of();
        String origin = origin(configuredUrl);
        Instant now = Instant.now(clock); List<PluginCatalogRepository.PluginEntry> entries = new ArrayList<>();
        for (var raw : rawFiles.entrySet()) {
            Map<String, Object> entry = object(raw.getValue()); if (entry.isEmpty()) continue;
            String id = String.valueOf(raw.getKey()), version = text(entry.get("version"));
            String pluginId = text(entry.get("plugin_id"));
            int suffix = id.lastIndexOf("-" + version);
            if (suffix > 0) pluginId = id.substring(0, suffix);
            if (pluginId.isBlank()) pluginId = id;
            String installUrl = text(entry.get("url"));
            if (installUrl.startsWith("/")) installUrl = origin + installUrl;
            entries.add(new PluginCatalogRepository.PluginEntry(id, CATALOG, pluginId, localized(entry.get("name")),
                localized(entry.get("description")), version, text(entry.get("author")), text(entry.get("platform")),
                text(entry.get("size")), installUrl, text(entry.get("sha256")), null, null, null, null, null,
                null, null, null, null, null, null, null, now));
        }
        return entries;
    }

    private List<PluginCatalogRepository.PluginEntry> fetchMarket(String base) {
        List<Map<String, Object>> all = new ArrayList<>(); int page = 1, total = Integer.MAX_VALUE;
        while (all.size() < total && page <= 10) {
            String separator = base.contains("?") ? "&" : "?";
            Map<String, Object> response = object(readJson(base + separator + "page_number=" + page + "&page_size=100", "plugin market"));
            if (!Boolean.TRUE.equals(response.get("success"))) throw new CatalogSyncException("Plugin market rejected the request: " + text(response.get("message")));
            Map<String, Object> data = object(response.get("data")); total = number(data.get("total"), 0);
            List<Map<String, Object>> items = objects(data.get("plugins")); if (items.isEmpty()) break;
            all.addAll(items); page++;
        }
        Instant now = Instant.now(clock); List<PluginCatalogRepository.PluginEntry> entries = new ArrayList<>(); int rank = 0;
        for (Map<String, Object> entry : all) {
            String id = text(entry.get("id")); if (id.isBlank()) continue;
            Object locales = entry.get("locales");
            entries.add(new PluginCatalogRepository.PluginEntry(id, MARKET, id.startsWith("@") ? id.substring(1) : id,
                text(entry.get("display_name")), localeValue(locales, "description"), text(entry.get("version")), null, null,
                null, null, null, localeValue(locales, "category"), text(entry.get("display_name")), text(entry.get("developer")),
                text(entry.get("owner")), null, nullableNumber(entry.get("downloads")), nullableNumber(entry.get("view_count")),
                text(entry.get("details_url")), Boolean.TRUE.equals(entry.get("is_featured")), json(entry.get("qwenpaw_compat_labels")),
                json(locales), rank++, now));
        }
        return entries;
    }

    private Map<String, Object> marketEntry(PluginCatalogRepository.PluginEntry entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", entry.id()); value.put("display_name", entry.displayName() == null ? entry.name() : entry.displayName());
        value.put("developer", entry.developer()); value.put("owner", entry.owner()); value.put("version", entry.version());
        value.put("logo_url", entry.logoUrl()); value.put("downloads", entry.downloads() == null ? 0 : entry.downloads());
        value.put("view_count", entry.viewCount() == null ? 0 : entry.viewCount()); value.put("details_url", entry.detailsUrl());
        value.put("locales", jsonObject(entry.locales())); value.put("qwenpaw_compat_labels", jsonList(entry.compatLabels()));
        value.put("is_featured", Boolean.TRUE.equals(entry.featured()));
        return value;
    }

    private Map<String, String> installedVersions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> plugin : plugins.list()) result.put(text(plugin.get("id")), text(plugin.get("version")));
        return result;
    }
    private boolean isUpgradeAvailable(String installed, String candidate) {
        if (installed == null || installed.isBlank() || candidate == null || candidate.isBlank()) return false;
        try { return compareVersions(candidate, installed) > 0; } catch (NumberFormatException ignored) { return !installed.equals(candidate); }
    }
    private int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i].replaceFirst("[^0-9].*$", "")) : 0;
            int y = i < b.length ? Integer.parseInt(b[i].replaceFirst("[^0-9].*$", "")) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    private PluginCatalogRepository.SyncConfig defaultConfig(String key) {
        return new PluginCatalogRepository.SyncConfig(key, CATALOG.equals(key) ? DEFAULT_CATALOG_URL : DEFAULT_MARKET_URL, null, 0, "pending");
    }
    private Map<String, Object> configMap(PluginCatalogRepository.SyncConfig value) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("key", value.key()); result.put("url", value.url());
        result.put("last_synced_at", value.lastSyncedAt() == null ? null : value.lastSyncedAt().toString());
        result.put("synced_count", value.syncedCount()); result.put("status", value.status()); return result;
    }
    private Object readJson(String url, String label) {
        try { String body = fetch.apply(url); return mapper.readValue(removeControls(body), new TypeReference<Object>() {}); }
        catch (CatalogSyncException exception) { throw exception; }
        catch (Exception exception) { throw new CatalogSyncException("Could not read " + label + ".", exception); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception exception) { return "{}"; } }
    @SuppressWarnings("unchecked") private Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> objects(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().filter(Map.class::isInstance).<Map<String, Object>>map(v -> new LinkedHashMap<>((Map<String, Object>) v)).toList(); }
    @SuppressWarnings("unchecked") private Map<String, Object> jsonObject(String value) { try { return value == null || value.isBlank() ? Map.of() : mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception ignored) { return Map.of(); } }
    @SuppressWarnings("unchecked") private List<Object> jsonList(String value) { try { return value == null || value.isBlank() ? List.of() : mapper.readValue(value, new TypeReference<List<Object>>() {}); } catch (Exception ignored) { return List.of(); } }
    private String localized(Object value) { Map<String, Object> map = object(value); return map.isEmpty() ? text(value) : text(map.getOrDefault("en-US", map.getOrDefault("en", map.getOrDefault("zh-CN", map.get("zh"))))); }
    private String localeValue(Object locales, String key) { for (String language : List.of("en", "en-US", "zh", "zh-CN")) { Object value = object(object(locales).get(language)).get(key); if (value != null) return text(value); } for (Object locale : object(locales).values()) { Object value = object(locale).get(key); if (value != null) return text(value); } return ""; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Integer number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static Long nullableNumber(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static String mapMarketCategory(String value) { if (value == null || value.isBlank()) return null; return switch (value) { case "app" -> "App"; case "agent-tool" -> "Tool"; case "provider" -> "Provider"; case "command" -> "Command"; case "hook" -> null; case "frontend" -> "Frontend"; case "general" -> "General"; default -> value; }; }
    private static String removeControls(String value) { return value == null ? "" : value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", ""); }
    private static URI resolveFromOrigin(String base, String path) { return URI.create(origin(base)).resolve(path); }
    private static String origin(String url) { URI uri = URI.create(url); return uri.getScheme() + "://" + uri.getAuthority(); }
    private static void validateUrl(String url) { try { URI uri = URI.create(Objects.requireNonNull(url).trim()); if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException("Sync URL must be an HTTP(S) URL."); } catch (IllegalArgumentException exception) { throw exception; } catch (Exception exception) { throw new IllegalArgumentException("Sync URL must be an HTTP(S) URL."); } }
    private static <T> T throwUnknown(String key) { throw new IllegalArgumentException("Unknown sync configuration: " + key); }

    private static final class HttpFetcher {
        private final HttpClient client; HttpFetcher(HttpClient client) { this.client = client; }
        String get(String url) { try { HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").header("User-Agent", "Majo/1.0").timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 400) throw new CatalogSyncException("Feed returned HTTP " + response.statusCode() + "."); return response.body(); } catch (IOException exception) { throw new CatalogSyncException("Feed request failed.", exception); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new CatalogSyncException("Feed request was interrupted.", exception); } }
    }
    public static final class CatalogSyncException extends RuntimeException { CatalogSyncException(String message) { super(message); } CatalogSyncException(String message, Throwable cause) { super(message, cause); } }
}
