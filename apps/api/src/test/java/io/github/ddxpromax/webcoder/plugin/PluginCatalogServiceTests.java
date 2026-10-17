package io.github.ddxpromax.webcoder.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PluginCatalogServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @Test void synchronizesOfficialFeedAndDerivesInstalledUpgradeState() {
        PluginCatalogRepository cache = mock(PluginCatalogRepository.class);
        PluginService plugins = mock(PluginService.class);
        when(cache.syncConfig("catalog")).thenReturn(Optional.of(new PluginCatalogRepository.SyncConfig(
            "catalog", "https://feed.example/metadata/index.json", null, 0, "pending")));
        when(plugins.list()).thenReturn(List.of(Map.of("id", "review", "version", "1.0.0")));
        PluginCatalogService service = service(cache, plugins, url -> {
            if (url.endsWith("/metadata/index.json")) return "{\"products\":{\"plugins\":{\"index_url\":\"/plugins/index.json\"}}}";
            return "{\"files\":{\"review-1.1.0\":{\"name\":{\"en-US\":\"Review\"},\"description\":{\"en\":\"Review code\"},\"version\":\"1.1.0\",\"url\":\"/dist/review.zip\"}}}";
        });

        assertThat(service.sync("catalog")).containsEntry("synced", 1);
        ArgumentCaptor<List<PluginCatalogRepository.PluginEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(cache).replace(eq("catalog"), entries.capture(), any());
        assertThat(entries.getValue()).singleElement().satisfies(entry -> {
            assertThat(entry.pluginId()).isEqualTo("review");
            assertThat(entry.installUrl()).isEqualTo("https://feed.example/dist/review.zip");
        });
        when(cache.list("catalog")).thenReturn(entries.getValue());
        Map<String, Object> result = service.catalog();
        @SuppressWarnings("unchecked") List<Map<String, Object>> catalog = (List<Map<String, Object>>) result.get("plugins");
        assertThat(catalog).singleElement().satisfies(entry -> {
            assertThat(entry.get("installed")).isEqualTo(true);
            assertThat(entry.get("upgrade_available")).isEqualTo(true);
        });
    }

    @Test void returnsFilteredAndPagedMarketRecords() {
        PluginCatalogRepository cache = mock(PluginCatalogRepository.class);
        when(cache.searchMarket("notes", "App")).thenReturn(List.of(
            market("a", "Notes", 5, 2, 2), market("b", "Notes Plus", 30, 1, 1)));
        PluginCatalogService service = service(cache, mock(PluginService.class), ignored -> "{}");

        Map<String, Object> result = service.searchMarket(1, 1, "notes", "app", "downloads");
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("plugins");
        assertThat(data).containsEntry("total", 2);
        assertThat(records).singleElement().satisfies(entry -> {
            assertThat(entry.get("id")).isEqualTo("b");
            assertThat(entry.get("downloads")).isEqualTo(30L);
        });
    }

    private PluginCatalogService service(PluginCatalogRepository cache, PluginService plugins,
                                         java.util.function.Function<String, String> fetch) {
        return new PluginCatalogService(cache, plugins, new ObjectMapper(), fetch, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PluginCatalogRepository.PluginEntry market(String id, String name, long downloads, long views, int rank) {
        return new PluginCatalogRepository.PluginEntry(id, "market", id, name, "A notes app", "1.0.0", null, null,
            null, null, null, "App", name, "Majo", "majo", null, downloads, views, null, false,
            "[]", "{\"en\":{\"description\":\"A notes app\",\"category\":\"App\"}}", rank, NOW);
    }
}
