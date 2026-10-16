package io.github.ddxpromax.webcoder.model;

import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo-compatible local llama.cpp discovery API. */
@RestController
@RequestMapping("/api/local-models")
@CrossOrigin(origins = "*")
public class LocalModelsController {
    private final MajoWorkingDirectory working;

    public LocalModelsController(MajoWorkingDirectory working) {
        this.working = working;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", findLlamaServer() != null, "models_dir", modelsDir().toString());
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> ignored) {
        return config();
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        if (!Files.isDirectory(modelsDir())) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var files = Files.list(modelsDir())) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (!modelFile(file.getFileName().toString())) continue;
                result.add(Map.of("id", file.getFileName().toString(), "name", file.getFileName().toString(),
                    "size_bytes", Files.size(file), "downloaded", true, "source", "auto"));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return result;
    }

    @GetMapping("/models/{modelId}")
    public Map<String, Object> detail(@PathVariable String modelId) {
        Path file = modelPath(modelId);
        boolean downloaded = file != null && Files.isRegularFile(file);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", modelId); result.put("name", modelId); result.put("downloaded", downloaded);
        result.put("size_bytes", downloaded ? file.toFile().length() : 0L);
        return result;
    }

    @DeleteMapping("/models/{modelId}")
    public Map<String, Object> delete(@PathVariable String modelId) {
        Path file = modelPath(modelId);
        try {
            if (file != null && Files.deleteIfExists(file)) return Map.of("status", "ok", "message", "Model deleted");
        } catch (Exception ignored) { }
        return Map.of("status", "error", "message", "Model not found");
    }

    @GetMapping("/models/download") public Map<String, Object> downloadProgress() { return idleDownload(); }
    @PostMapping("/models/download") public Map<String, Object> download(@RequestBody Map<String, Object> ignored) { return unsupported("Model download requires llama.cpp integration"); }
    @DeleteMapping("/models/download") public Map<String, Object> cancelDownload() { return Map.of("status", "ok", "message", "No active download"); }

    @GetMapping("/server")
    public Map<String, Object> server() {
        String binary = findLlamaServer();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false); result.put("installable", binary != null); result.put("installed", binary != null);
        result.put("port", null); result.put("model_name", null);
        result.put("message", binary == null ? "llama-server not found in PATH; install llama.cpp to use local models"
            : "llama.cpp server is not running, please start the server first");
        return result;
    }

    @PostMapping("/server") public Map<String, Object> startServer(@RequestBody Map<String, Object> ignored) { return unsupported("llama.cpp server startup requires integration"); }
    @DeleteMapping("/server") public Map<String, Object> stopServer() { return Map.of("status", "ok", "message", "No server running"); }
    @GetMapping("/server/download") public Map<String, Object> serverDownloadProgress() { return idleDownload(); }
    @PostMapping("/server/download") public Map<String, Object> downloadServer() { return unsupported("llama.cpp download requires integration"); }
    @DeleteMapping("/server/download") public Map<String, Object> cancelServerDownload() { return Map.of("status", "ok", "message", "No active download"); }
    @GetMapping("/server/update") public Map<String, Object> serverUpdate() { Map<String,Object> result=new LinkedHashMap<>(); result.put("has_update",false); result.put("current_version",null); result.put("latest_version",null); return result; }

    private Path modelsDir() { return working.resolve("local-models"); }
    private Path modelPath(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")) return null;
        Path root = modelsDir().toAbsolutePath().normalize();
        Path file = root.resolve(id).normalize();
        return file.startsWith(root) ? file : null;
    }
    private String findLlamaServer() {
        String value = System.getenv("PATH"); if (value == null) return null;
        for (String dir : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) for (String name : List.of("llama-server", "llama-server.exe")) {
            Path candidate = Path.of(dir, name); if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }
    private boolean modelFile(String name) { String lower = name.toLowerCase(); return lower.endsWith(".gguf") || lower.endsWith(".bin") || lower.endsWith(".safetensors"); }
    private Map<String, Object> unsupported(String message) { return Map.of("status", "not_supported", "message", message); }
    private Map<String, Object> idleDownload() { Map<String,Object> result=new LinkedHashMap<>(); result.put("status","idle"); result.put("model_name",null); result.put("downloaded_bytes",0); result.put("total_bytes",null); result.put("speed_bytes_per_sec",0.0); result.put("source",null); result.put("error",null); result.put("local_path",null); return result; }
}
