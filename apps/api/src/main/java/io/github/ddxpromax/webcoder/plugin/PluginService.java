package io.github.ddxpromax.webcoder.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/** Majo plugin installation and filesystem registry for ordinary and PawApp plugins. */
@Service
public class PluginService {
    private final MajoWorkingDirectory working;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public PluginService(MajoWorkingDirectory working, ObjectMapper mapper) { this.working = working; this.mapper = mapper; }

    public List<Map<String, Object>> list() {
        if (!Files.isDirectory(root())) return List.of();
        try (var paths = Files.list(root())) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Path directory : paths.filter(Files::isDirectory).sorted().toList()) {
                Map<String, Object> manifest = manifest(directory);
                if (!manifest.isEmpty()) result.add(info(directory, manifest));
            }
            return result;
        } catch (IOException exception) { return List.of(); }
    }

    public Map<String, Object> install(String source) throws IOException, InterruptedException {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        Path supplied;
        boolean temporary = false;
        if (source.startsWith("https://") || source.startsWith("http://")) {
            supplied = Files.createTempFile("majo-plugin-", ".zip"); temporary = true;
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofFile(supplied));
            if (response.statusCode() >= 400) { Files.deleteIfExists(supplied); throw new IOException("Download failed: HTTP " + response.statusCode()); }
        } else supplied = Path.of(source).toAbsolutePath().normalize();
        try {
            if (!Files.exists(supplied)) throw new IllegalArgumentException("Path not found: " + source);
            Path installed = Files.isDirectory(supplied) ? copyDirectory(supplied) : extractZip(supplied);
            Map<String, Object> manifest = manifest(installed);
            Map<String, Object> result = info(installed, manifest);
            result.put("status", "installed");
            return result;
        } finally { if (temporary) Files.deleteIfExists(supplied); }
    }

    public boolean uninstall(String id) throws IOException {
        Path directory = installedDirectory(id);
        if (directory == null || !Files.isDirectory(directory)) return false;
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        return true;
    }

    public String file(String id, String relativePath) throws IOException {
        Path directory = installedDirectory(id);
        if (directory == null || relativePath == null) throw new IllegalArgumentException("File not found");
        Path file = directory.resolve(relativePath).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) throw new IllegalArgumentException("File not found");
        return Files.readString(file);
    }

    /** Lists only installed plugins that declare a browser-side entry point. */
    public List<Map<String, Object>> frontendPlugins() {
        return list().stream()
            .filter(plugin -> plugin.get("frontend_entry") instanceof String entry && !entry.isBlank())
            .toList();
    }

    /** Resolves a frontend asset without allowing a plugin to escape its own directory. */
    public PluginAsset frontendAsset(String id, String relativePath) throws IOException {
        Path directory = installedDirectory(id);
        if (directory == null || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Plugin asset not found");
        }
        Path asset = directory.resolve(relativePath).normalize();
        if (!asset.startsWith(directory) || !Files.isRegularFile(asset)) {
            throw new IllegalArgumentException("Plugin asset not found");
        }
        return new PluginAsset(asset, Files.readAllBytes(asset));
    }

    private Path copyDirectory(Path source) throws IOException {
        String id = source.getFileName() == null ? UUID.randomUUID().toString() : source.getFileName().toString();
        Path destination = requireDirectory(id);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path)).normalize();
                if (!target.startsWith(destination)) throw new IOException("Invalid plugin path");
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        return destination;
    }

    private Path extractZip(Path zip) throws IOException {
        String topLevel = null;
        Path staging = Files.createTempDirectory(root().getParent(), "plugin-stage-");
        try {
            try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(zip))) {
                for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) throw new IOException("Invalid ZIP entry");
                    String first = entry.getName().split("/", 2)[0];
                    if (!first.isBlank()) topLevel = topLevel == null ? first : topLevel;
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else { Files.createDirectories(target.getParent()); Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            if (topLevel == null || !validId(topLevel)) throw new IOException("Plugin ZIP has no valid top-level directory");
            Path unpacked = staging.resolve(topLevel); if (!Files.isDirectory(unpacked)) throw new IOException("Plugin ZIP must contain a top-level directory");
            Path destination = requireDirectory(topLevel); deleteTree(destination); Files.move(unpacked, destination, StandardCopyOption.REPLACE_EXISTING); return destination;
        } finally { deleteTree(staging); }
    }

    private Map<String, Object> info(Path directory, Map<String, Object> manifest) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", string(manifest.getOrDefault("id", directory.getFileName().toString())));
        result.put("name", string(manifest.getOrDefault("name", directory.getFileName().toString())));
        result.put("version", string(manifest.getOrDefault("version", "0.0.0")));
        result.put("description", string(manifest.get("description")));
        result.put("author", string(manifest.get("author")));
        result.put("enabled", true); result.put("loaded", true);
        result.put("plugin_type", string(manifest.getOrDefault("plugin_type", "unknown")));
        result.put("frontend_entry", map(manifest.get("entry")).get("frontend"));
        return result;
    }

    private Map<String, Object> manifest(Path directory) {
        Path file = directory.resolve("plugin.json"); if (!Files.isRegularFile(file)) return Map.of();
        try { return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (IOException exception) { return Map.of(); }
    }

    private Path root() { return working.resolve("plugins"); }
    private Path requireDirectory(String id) throws IOException { if (!validId(id)) throw new IllegalArgumentException("Invalid plugin id"); Path root = root().toAbsolutePath().normalize(); Files.createDirectories(root); Path directory = root.resolve(id).normalize(); if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid plugin id"); return directory; }
    private Path installedDirectory(String id) {
        try {
            Path direct = requireDirectory(id);
            if (Files.isDirectory(direct)) return direct;
            if (!Files.isDirectory(root())) return null;
            try (var paths = Files.list(root())) {
                return paths.filter(Files::isDirectory).filter(path -> matches(path, id)).findFirst().orElse(null);
            }
        } catch (Exception exception) { return null; }
    }
    private boolean matches(Path directory, String id) {
        if (directory.getFileName().toString().equals(id) || directory.getFileName().toString().startsWith(id + "-")) return true;
        return id.equals(string(manifest(directory).get("id")));
    }
    private boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9._-]+") && !id.equals(".") && !id.equals(".."); }
    private void deleteTree(Path directory) throws IOException { if (!Files.exists(directory)) return; try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of(); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    public record PluginAsset(Path path, byte[] content) { }
}
