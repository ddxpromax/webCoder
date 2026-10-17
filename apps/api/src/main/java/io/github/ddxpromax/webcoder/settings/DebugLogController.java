package io.github.ddxpromax.webcoder.settings;

import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only bounded tail of Majo's backend daemon log. */
@RestController
@RequestMapping("/api")
public class DebugLogController {
    private static final int MAX_LINES = 1_000;
    private static final int MAX_TAIL_BYTES = 512 * 1024;
    private final Path logFile;

    public DebugLogController(MajoWorkingDirectory working) {
        this(working.root().resolve("majo.log"));
    }

    DebugLogController(Path logFile) { this.logFile = logFile.toAbsolutePath().normalize(); }

    @GetMapping("/console/debug/backend-logs")
    public Map<String, Object> logs(@RequestParam(defaultValue = "200") int lines) {
        int limit = Math.max(20, Math.min(lines, MAX_LINES));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", logFile.toString()); result.put("lines", limit);
        try {
            BasicFileAttributes attrs = Files.readAttributes(logFile, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) return missing(result);
            result.put("exists", true); result.put("updated_at", attrs.lastModifiedTime().toInstant().getEpochSecond());
            result.put("size", attrs.size()); result.put("content", tail(limit)); return result;
        } catch (Exception ignored) { return missing(result); }
    }

    @GetMapping("/agents/{agentId}/console/debug/backend-logs")
    public Map<String, Object> agentLogs(@PathVariable String agentId, @RequestParam(defaultValue = "200") int lines) { return logs(lines); }

    private Map<String, Object> missing(Map<String, Object> result) {
        result.put("exists", false); result.put("updated_at", null); result.put("size", 0); result.put("content", ""); return result;
    }

    private String tail(int lines) throws Exception {
        long size = Files.size(logFile); if (size == 0) return "";
        long offset = Math.max(0, size - MAX_TAIL_BYTES); byte[] bytes = new byte[(int) (size - offset)];
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) { file.seek(offset); file.readFully(bytes); }
        String[] all = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
        return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }
}
