package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class WorkspaceFilesController {
    private static final Set<String> SKIP_NAMES = Set.of(
        ".git", "__pycache__", ".venv", "node_modules",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".hypothesis", "target", ".idea"
    );
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Map<String, String> BINARY_MIME = Map.ofEntries(
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("svg", "image/svg+xml"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("csv", "text/csv")
    );

    private final AgentStore agents;

    public WorkspaceFilesController(AgentStore agents) {
        this.agents = agents;
    }

    @GetMapping("/workspace/code-files")
    public List<Map<String, Object>> listCodeFiles(
        HttpServletRequest request,
        @RequestParam(required = false) String root
    ) {
        return listCodeFiles(browseRoot(agentId(request), root));
    }

    @GetMapping("/agents/{agentId}/workspace/code-files")
    public List<Map<String, Object>> agentCodeFiles(
        @PathVariable String agentId
    ) {
        return listCodeFiles(agents.workspaceDirForAgent(agentId));
    }

    @GetMapping("/workspace/code-files/**")
    public ResponseEntity<?> readCodeFile(
        HttpServletRequest request,
        @RequestParam(required = false) String root
    ) {
        return readCodeFile(
            browseRoot(agentId(request), root),
            relativePath(request, "/workspace/code-files/")
        );
    }

    @GetMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentReadCodeFile(
        @PathVariable String agentId,
        HttpServletRequest request
    ) {
        return readCodeFile(
            agents.workspaceDirForAgent(agentId),
            relativePath(request, "/workspace/code-files/")
        );
    }

    @PutMapping("/workspace/code-files/**")
    public ResponseEntity<?> writeCodeFile(
        HttpServletRequest request,
        @RequestBody Map<String, String> body,
        @RequestParam(required = false) String root
    ) {
        return writeCodeFile(
            browseRoot(agentId(request), root),
            relativePath(request, "/workspace/code-files/"),
            body
        );
    }

    @PutMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentWriteCodeFile(
        @PathVariable String agentId,
        HttpServletRequest request,
        @RequestBody Map<String, String> body
    ) {
        return writeCodeFile(
            agents.workspaceDirForAgent(agentId),
            relativePath(request, "/workspace/code-files/"),
            body
        );
    }

    @GetMapping("/workspace/files")
    public List<Map<String, Object>> listMarkdownFiles(
        HttpServletRequest request
    ) {
        return listMarkdownFiles(workspace(agentId(request)));
    }

    @GetMapping("/agents/{agentId}/workspace/files")
    public List<Map<String, Object>> agentMarkdownFiles(
        @PathVariable String agentId
    ) {
        return listMarkdownFiles(workspace(agentId));
    }

    @GetMapping("/workspace/files/{name}")
    public ResponseEntity<?> readMarkdownFile(
        @PathVariable String name,
        HttpServletRequest request
    ) {
        return readMarkdownFile(workspace(agentId(request)), name);
    }

    @GetMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentReadMarkdownFile(
        @PathVariable String agentId,
        @PathVariable String name
    ) {
        return readMarkdownFile(workspace(agentId), name);
    }

    @PutMapping("/workspace/files/{name}")
    public ResponseEntity<?> writeMarkdownFile(
        @PathVariable String name,
        @RequestBody Map<String, String> body,
        HttpServletRequest request
    ) {
        return writeMarkdownFile(
            workspace(agentId(request)), name, body
        );
    }

    @PutMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentWriteMarkdownFile(
        @PathVariable String agentId,
        @PathVariable String name,
        @RequestBody Map<String, String> body
    ) {
        return writeMarkdownFile(workspace(agentId), name, body);
    }

    @GetMapping("/workspace/memory")
    public List<Map<String, Object>> memoryFiles(
        @RequestParam(required = false) String section,
        HttpServletRequest request
    ) {
        return listMemoryMarkdownFiles(
            workspace(agentId(request)).resolve("memory"),
            section
        );
    }

    @GetMapping("/agents/{agentId}/workspace/memory")
    public List<Map<String, Object>> agentMemoryFiles(
        @PathVariable String agentId,
        @RequestParam(required = false) String section
    ) {
        return listMemoryMarkdownFiles(
            workspace(agentId).resolve("memory"), section
        );
    }

    @GetMapping("/workspace/memory/**")
    public ResponseEntity<?> readMemoryFile(
        HttpServletRequest request
    ) {
        return readMemoryMarkdownFile(
            workspace(agentId(request)).resolve("memory"),
            relativePath(request, "/workspace/memory/")
        );
    }

    @GetMapping("/agents/{agentId}/workspace/memory/**")
    public ResponseEntity<?> agentReadMemoryFile(
        @PathVariable String agentId,
        HttpServletRequest request
    ) {
        return readMemoryMarkdownFile(
            workspace(agentId).resolve("memory"),
            relativePath(request, "/workspace/memory/")
        );
    }

    @PutMapping("/workspace/memory/**")
    public ResponseEntity<?> writeMemoryFile(
        @RequestBody Map<String, String> body,
        HttpServletRequest request
    ) {
        return writeMemoryMarkdownFile(
            workspace(agentId(request)).resolve("memory"),
            relativePath(request, "/workspace/memory/"), body
        );
    }

    @PutMapping("/agents/{agentId}/workspace/memory/**")
    public ResponseEntity<?> agentWriteMemoryFile(
        @PathVariable String agentId,
        @RequestBody Map<String, String> body,
        HttpServletRequest request
    ) {
        return writeMemoryMarkdownFile(
            workspace(agentId).resolve("memory"),
            relativePath(request, "/workspace/memory/"), body
        );
    }

    @GetMapping("/workspace/binary-files/**")
    public ResponseEntity<Resource> binaryFile(
        HttpServletRequest request,
        @RequestParam(required = false) String root
    ) {
        return binaryFile(
            browseRoot(agentId(request), root),
            relativePath(request, "/workspace/binary-files/")
        );
    }

    @GetMapping("/agents/{agentId}/workspace/binary-files/**")
    public ResponseEntity<Resource> agentBinaryFile(
        @PathVariable String agentId,
        HttpServletRequest request
    ) {
        return binaryFile(
            workspace(agentId),
            relativePath(request, "/workspace/binary-files/")
        );
    }

    private Path workspace(String agentId) {
        return agents.workspaceDirForAgent(agentId);
    }

    private Path browseRoot(String agentId, String root) {
        Path workspace = workspace(agentId);
        if ("workspace".equals(root)) {
            return workspace;
        }
        String projectDir = agents.getProjectDir(agentId);
        if (projectDir != null && !projectDir.isBlank()) {
            try {
                Path project = Path.of(projectDir).toAbsolutePath()
                    .normalize();
                if (Files.isDirectory(project)) {
                    return project;
                }
            } catch (RuntimeException ignored) {
                // A missing or malformed project falls back to the workspace.
            }
        }
        return workspace;
    }

    private List<Map<String, Object>> listCodeFiles(Path root) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
                ) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return skipped(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) {
                    if (!skipped(file.getFileName().toString())
                        && attrs.isRegularFile()) {
                        result.add(fileEntry(root, file, attrs));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to list workspace files", exception
            );
        }
        return result;
    }

    private ResponseEntity<?> readCodeFile(
        Path root,
        String relative
    ) {
        Path target = safePath(root, relative);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Path traversal"
            ));
        }
        if (!Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        if (Files.isDirectory(target)) {
            List<Map<String, Object>> children = new ArrayList<>();
            try (var entries = Files.list(target)) {
                entries.filter(path -> !skipped(path.getFileName()
                    .toString()))
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(
                                path, BasicFileAttributes.class
                            );
                            Map<String, Object> item =
                                fileEntry(root, path, attrs);
                            item.put("is_dir", attrs.isDirectory());
                            children.add(item);
                        } catch (IOException ignored) {
                            // A file removed mid-list is simply omitted.
                        }
                    });
            } catch (IOException exception) {
                return ResponseEntity.internalServerError().body(
                    Map.of("error", exception.getMessage())
                );
            }
            return ResponseEntity.ok(children);
        }
        try {
            if (Files.size(target) > 5L * 1024 * 1024) {
                return ResponseEntity.status(413).body(Map.of(
                    "error", "File too large (>5MB)"
                ));
            }
            return ResponseEntity.ok(Map.of(
                "content", Files.readString(target)
            ));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private ResponseEntity<?> writeCodeFile(
        Path root,
        String relative,
        Map<String, String> body
    ) {
        Path target = safePath(root, relative);
        if (target == null || target.equals(root)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Path traversal"
            ));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                body.getOrDefault("content", ""));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private List<Map<String, Object>> listMarkdownFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (var entries = Files.list(root)) {
            entries.filter(Files::isRegularFile)
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> path.getFileName().toString()
                    .endsWith(".md"))
                .filter(path -> !skipped(path.getFileName()
                    .toString()))
                .forEach(path -> {
                    try {
                        result.add(fileEntry(
                            root, path,
                            Files.readAttributes(
                                path, BasicFileAttributes.class
                            )
                        ));
                    } catch (IOException ignored) {
                        // A file removed mid-list is simply omitted.
                    }
                });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to list markdown files", exception
            );
        }
        return result;
    }

    private List<Map<String, Object>> listMemoryMarkdownFiles(
        Path root,
        String section
    ) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (var entries = Files.walk(root)) {
            entries.filter(Files::isRegularFile)
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> path.getFileName().toString()
                    .endsWith(".md"))
                .filter(path -> !skipped(path.getFileName()
                    .toString()))
                .filter(path -> {
                    String relative = root.relativize(path)
                        .toString().replace('\\', '/');
                    if ("daily".equals(section)) {
                        return !relative.startsWith("digest/");
                    }
                    if ("digest".equals(section)) {
                        return relative.startsWith("digest/");
                    }
                    return true;
                })
                .forEach(path -> {
                    try {
                        result.add(fileEntry(root, path,
                            Files.readAttributes(path,
                                BasicFileAttributes.class)));
                    } catch (IOException ignored) {
                        // A file removed mid-list is omitted.
                    }
                });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to list memory files", exception
            );
        }
        return result;
    }

    private ResponseEntity<?> readMemoryMarkdownFile(
        Path root,
        String relative
    ) {
        Path target = safeMemoryPath(root, relative);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid memory path"
            ));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(Map.of(
                "content", Files.readString(target)
            ));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private ResponseEntity<?> writeMemoryMarkdownFile(
        Path root,
        String relative,
        Map<String, String> body
    ) {
        Path target = safeMemoryPath(root, relative);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid memory path"
            ));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                body.getOrDefault("content", ""));
            return ResponseEntity.ok(Map.of("written", true));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private static Path safeMemoryPath(
        Path root,
        String relative
    ) {
        if (relative == null || relative.isBlank()
            || !relative.endsWith(".md")) {
            return null;
        }
        Path target = safePath(root, relative);
        return target == null || target.equals(
            root.toAbsolutePath().normalize()
        ) ? null : target;
    }

    private ResponseEntity<?> readMarkdownFile(
        Path root,
        String name
    ) {
        Path target = safePath(root, name);
        if (target == null || target.getParent() == null
            || !target.getParent().equals(root)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid path"
            ));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(Map.of(
                "content", Files.readString(target)
            ));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private ResponseEntity<?> writeMarkdownFile(
        Path root,
        String name,
        Map<String, String> body
    ) {
        Path target = safePath(root, name);
        if (target == null || target.getParent() == null
            || !target.getParent().equals(root)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid path"
            ));
        }
        try {
            Files.createDirectories(root);
            Files.writeString(target,
                body.getOrDefault("content", ""));
            return ResponseEntity.ok(Map.of("written", true));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", exception.getMessage()
            ));
        }
    }

    private ResponseEntity<Resource> binaryFile(
        Path root,
        String relative
    ) {
        Path target = safePath(root, relative);
        if (target == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        int dot = relative.lastIndexOf('.');
        String extension = dot < 0 ? ""
            : relative.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = BINARY_MIME.get(extension);
        if (mime == null) {
            return ResponseEntity.status(415).build();
        }
        try {
            if (Files.size(target) > 50L * 1024 * 1024) {
                return ResponseEntity.status(413).build();
            }
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header("Content-Disposition", "inline")
                .body(new ByteArrayResource(
                    Files.readAllBytes(target)
                ));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static Path safePath(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) {
            return null;
        }
        Path cursor = normalizedRoot;
        if (Files.isSymbolicLink(cursor)) {
            return null;
        }
        for (Path part : normalizedRoot.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                return null;
            }
        }
        return target;
    }

    private static Map<String, Object> fileEntry(
        Path root,
        Path file,
        BasicFileAttributes attrs
    ) {
        String relative = root.relativize(file).toString()
            .replace('\\', '/');
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("filename", relative);
        entry.put("path", relative);
        entry.put("size", attrs.size());
        entry.put("modified_time", ISO.format(
            Instant.ofEpochMilli(
                attrs.lastModifiedTime().toMillis()
            ).atZone(ZoneId.systemDefault())
        ));
        return entry;
    }

    private static boolean skipped(String name) {
        return name.startsWith(".") || SKIP_NAMES.contains(name);
    }

    private static String relativePath(
        HttpServletRequest request,
        String marker
    ) {
        String uri = request.getRequestURI();
        int start = uri.indexOf(marker);
        if (start < 0) {
            return "";
        }
        return UriUtils.decode(
            uri.substring(start + marker.length()),
            StandardCharsets.UTF_8
        );
    }

    private static String agentId(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String parameter = request.getParameter("agent");
        return parameter == null || parameter.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : parameter;
    }
}
