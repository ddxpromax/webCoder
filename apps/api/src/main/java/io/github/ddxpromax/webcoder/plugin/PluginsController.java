package io.github.ddxpromax.webcoder.plugin;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Core Majo plugin registry endpoints. Marketplace synchronization remains separate from local installation. */
@RestController @RequestMapping("/api/plugins") @CrossOrigin(origins = "*")
public class PluginsController {
    private final PluginService plugins;
    public PluginsController(PluginService plugins) { this.plugins = plugins; }
    @GetMapping public List<Map<String, Object>> list() { return plugins.list(); }
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Map<String, Object> body) { try { return ResponseEntity.ok(plugins.install(String.valueOf(body.getOrDefault("source", "")))); } catch (IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage())); } catch (Exception exception) { return ResponseEntity.internalServerError().body(Map.of("detail", "Install failed: " + exception.getMessage())); } }
    @PostMapping("/upload") public ResponseEntity<?> upload() { return ResponseEntity.badRequest().body(Map.of("detail", "Upload not supported, use install with source path")); }
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id) { try { return plugins.uninstall(id) ? ResponseEntity.ok(Map.of("id", id, "message", "Plugin '" + id + "' uninstalled.")) : ResponseEntity.notFound().build(); } catch (Exception exception) { return ResponseEntity.internalServerError().body(Map.of("detail", exception.getMessage())); } }
    @GetMapping("/{id}/files/{path:.*}") public ResponseEntity<?> file(@PathVariable String id, @PathVariable String path) { try { return ResponseEntity.ok(Map.of("content", plugins.file(id, path))); } catch (Exception exception) { return ResponseEntity.status(404).body(Map.of("detail", "File not found")); } }
    @GetMapping("/{id}/status") public Map<String, Object> status(@PathVariable String id) { return Map.of("id", id, "loaded", plugins.list().stream().anyMatch(plugin -> id.equals(plugin.get("id"))), "enabled", true); }
    @GetMapping("/catalog") public Map<String, Object> catalog() { return Map.of("plugins", List.of(), "updated_at", null, "error", null); }
}
