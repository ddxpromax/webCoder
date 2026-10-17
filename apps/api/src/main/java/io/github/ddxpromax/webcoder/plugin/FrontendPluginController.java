package io.github.ddxpromax.webcoder.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Majo-compatible endpoint for browser-side plugin bundles. */
@RestController
@RequestMapping("/api/frontend_plugin")
@CrossOrigin(origins = "*")
public class FrontendPluginController {
    private final PluginService plugins;
    public FrontendPluginController(PluginService plugins) { this.plugins = plugins; }

    @GetMapping
    public List<Map<String, Object>> list() { return plugins.frontendPlugins(); }

    @GetMapping("/{id}/files/{path:.*}")
    public ResponseEntity<?> file(@PathVariable String id, @PathVariable String path) {
        try {
            PluginService.PluginAsset asset = plugins.frontendAsset(id, path);
            return ResponseEntity.ok().contentType(mediaType(asset.path())).body(asset.content());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(404).body(Map.of("detail", "Plugin asset not found"));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of("detail", "Could not read plugin asset"));
        }
    }

    private static MediaType mediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".js") || name.endsWith(".mjs")) return MediaType.valueOf("application/javascript");
        if (name.endsWith(".css")) return MediaType.valueOf("text/css");
        if (name.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (name.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
