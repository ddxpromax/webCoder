package io.github.ddxpromax.webcoder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Majo chat attachment upload and inline preview endpoints. */
@RestController
@RequestMapping("/api")
public class ConsoleUploadController {
    private static final Logger log = LoggerFactory.getLogger(
        ConsoleUploadController.class
    );
    private static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final Map<String, String> MIME = Map.ofEntries(
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("svg", "image/svg+xml"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("txt", "text/plain"),
        Map.entry("md", "text/markdown"),
        Map.entry("csv", "text/csv"),
        Map.entry("json", "application/json"),
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("wav", "audio/wav"),
        Map.entry("ogg", "audio/ogg"),
        Map.entry("m4a", "audio/mp4"),
        Map.entry("mp4", "video/mp4"),
        Map.entry("webm", "video/webm"),
        Map.entry("mov", "video/quicktime")
    );

    private final MajoWorkingDirectory workingDirectory;

    public ConsoleUploadController(
        MajoWorkingDirectory workingDirectory
    ) {
        this.workingDirectory = workingDirectory;
    }

    @PostMapping("/console/upload")
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "file is required"
            ));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.status(413).body(Map.of(
                "detail", "File exceeds the 100 MB limit"
            ));
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            original = "file";
        }
        original = original.replace('\\', '/');
        if (original.contains("/")) {
            original = original.substring(
                original.lastIndexOf('/') + 1
            );
        }
        try {
            Path directory = uploadsDir();
            Files.createDirectories(directory);
            int dot = original.lastIndexOf('.');
            String stem = dot > 0
                ? original.substring(0, dot) : original;
            String extension = dot > 0
                ? original.substring(dot) : "";
            String storedName = UUID.randomUUID().toString()
                .replace("-", "") + "_" + sanitize(stem)
                + extension;
            Path target = directory.resolve(storedName)
                .normalize();
            if (!target.startsWith(directory)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "detail", "Unsafe filename"
                ));
            }
            try (var input = file.getInputStream()) {
                Files.copy(input, target,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", target.toString());
            result.put("file_name", original);
            result.put("stored_name", storedName);
            result.put("size", Files.size(target));
            return ResponseEntity.ok(result);
        } catch (IOException exception) {
            log.warn("Console upload failed", exception);
            return ResponseEntity.internalServerError().body(Map.of(
                "detail", String.valueOf(exception.getMessage())
            ));
        }
    }

    @GetMapping({
        "/files/preview/{fileName:.+}",
        "/files/preview/{fileName:.+}/"
    })
    public ResponseEntity<?> preview(
        @PathVariable String fileName
    ) {
        Path directory = uploadsDir();
        Path target = directory.resolve(
            fileName.replace('\\', '/')
        ).normalize();
        if (!target.startsWith(directory)
            || Files.isSymbolicLink(target)) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "Path traversal"
            ));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.getFileName().toString()
            .toLowerCase();
        int dot = name.lastIndexOf('.');
        String extension = dot > 0
            ? name.substring(dot + 1) : "";
        String mime = MIME.get(extension);
        MediaType type = mime == null
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(mime);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL,
                "no-cache, no-store, must-revalidate")
            .contentType(type)
            .body(new FileSystemResource(target));
    }

    private Path uploadsDir() {
        return workingDirectory.resolve("uploads")
            .toAbsolutePath().normalize();
    }

    private static String sanitize(String value) {
        Set<Character> extra = Set.of(
            '-', '_', '.', ' '
        );
        StringBuilder name = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (Character.isLetterOrDigit(character)
                || extra.contains(character)) {
                name.append(character);
            }
        }
        return name.isEmpty() ? "file" : name.toString();
    }
}
