package io.github.ddxpromax.webcoder.plugin;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Core Majo plugin registry endpoints. Marketplace synchronization remains separate from local installation. */
@RestController @RequestMapping("/api/plugins") @CrossOrigin(origins = "*")
public class PluginsController {
    private final PluginService plugins;
    private final PluginCatalogService catalog;
    public PluginsController(PluginService plugins, PluginCatalogService catalog) { this.plugins = plugins; this.catalog = catalog; }
    @GetMapping public List<Map<String, Object>> list() { return plugins.list(); }
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Map<String, Object> body) { try { return ResponseEntity.ok(plugins.install(String.valueOf(body.getOrDefault("source", "")))); } catch (IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage())); } catch (Exception exception) { return ResponseEntity.internalServerError().body(Map.of("detail", "Install failed: " + exception.getMessage())); } }
    @PostMapping("/upload") public ResponseEntity<?> upload() { return ResponseEntity.badRequest().body(Map.of("detail", "Upload not supported, use install with source path")); }
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id) { try { return plugins.uninstall(id) ? ResponseEntity.ok(Map.of("id", id, "message", "Plugin '" + id + "' uninstalled.")) : ResponseEntity.notFound().build(); } catch (Exception exception) { return ResponseEntity.internalServerError().body(Map.of("detail", exception.getMessage())); } }
    @GetMapping("/{id}/files/{path:.*}") public ResponseEntity<?> file(@PathVariable String id, @PathVariable String path) { try { return ResponseEntity.ok(Map.of("content", plugins.file(id, path))); } catch (Exception exception) { return ResponseEntity.status(404).body(Map.of("detail", "File not found")); } }
    @GetMapping("/{id}/status") public Map<String, Object> status(@PathVariable String id) { return Map.of("id", id, "loaded", plugins.list().stream().anyMatch(plugin -> id.equals(plugin.get("id"))), "enabled", true); }
    @GetMapping("/catalog") public Map<String, Object> catalog() { return catalog.catalog(); }
    @PostMapping("/catalog/sync") public ResponseEntity<?> syncCatalog() { return sync("catalog"); }
    @PostMapping("/market/sync") public ResponseEntity<?> syncMarket() { return sync("market"); }
    @GetMapping("/market/search") public Map<String, Object> searchMarket(
        @RequestParam(defaultValue = "1", name = "page_number") int pageNumber,
        @RequestParam(defaultValue = "20", name = "page_size") int pageSize,
        @RequestParam(required = false) String search, @RequestParam(required = false) String category,
        @RequestParam(required = false, name = "sort_by") String sortBy
    ) { return catalog.searchMarket(pageNumber, pageSize, search, category, sortBy); }
    @GetMapping("/sync-config") public List<Map<String, Object>> syncConfigs() { return catalog.syncConfigs(); }
    @PutMapping("/sync-config") public ResponseEntity<?> updateSyncConfig(@RequestBody Map<String, Object> body) {
        try { return ResponseEntity.ok(catalog.updateSyncConfig(String.valueOf(body.getOrDefault("key", "")), String.valueOf(body.getOrDefault("url", "")))); }
        catch (IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage())); }
    }
    private ResponseEntity<?> sync(String key) {
        try { return ResponseEntity.ok(catalog.sync(key)); }
        catch (PluginCatalogService.CatalogSyncException exception) { return ResponseEntity.status(502).body(Map.of("detail", exception.getMessage())); }
        catch (IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage())); }
    }
}
