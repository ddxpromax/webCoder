package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class WorkspaceTransferController {
    private static final Set<String> SKIP_NAMES = Set.of(
        ".git", "__pycache__", ".venv", "node_modules",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".hypothesis", "target", ".idea"
    );
    private static final Set<String> ZIP_TYPES = Set.of(
        "application/zip", "application/x-zip-compressed",
        "application/octet-stream"
    );
    private static final long MAX_EXPANDED_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;

    private final AgentStore agents;

    public WorkspaceTransferController(AgentStore agents) {
        this.agents = agents;
    }

    @GetMapping("/workspace/download")
    public ResponseEntity<Resource> download(
        HttpServletRequest request
    ) {
        return download(agents.workspaceDirForAgent(agentId(request)));
    }

    @GetMapping("/agents/{agentId}/workspace/download")
    public ResponseEntity<Resource> agentDownload(
        @PathVariable String agentId
    ) {
        return download(agents.workspaceDirForAgent(agentId));
    }

    @PostMapping("/workspace/upload")
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file,
        HttpServletRequest request
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "file is required"
            ));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
            && !ZIP_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Uploaded file is not a valid zip archive"
            ));
        }
        try {
            extractAndMergeZip(
                file,
                agents.workspaceDirForAgent(agentId(request))
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                "detail", "Failed to extract zip: "
                    + exception.getMessage()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Workspace updated from zip"
        ));
    }

    @PostMapping("/workspace/file-upload")
    public ResponseEntity<Map<String, Object>> uploadFiles(
        @RequestParam("files") MultipartFile[] files,
        @RequestParam(defaultValue = "") String path,
        @RequestParam(required = false) String conflict,
        HttpServletRequest request
    ) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "files are required"
            ));
        }
        if (conflict != null && !conflict.isBlank()
            && !Set.of("overwrite", "skip", "rename")
                .contains(conflict)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "conflict must be overwrite, skip, or rename"
            ));
        }
        Path workspace = agents.workspaceDirForAgent(agentId(request));
        Path directory;
        try {
            directory = resolveUploadDirectory(workspace, path);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        }
        List<String> conflicts = new ArrayList<>();
        Map<String, MultipartFile> prepared = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) {
                continue;
            }
            name = name.replace('\\', '/');
            if (name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            if (name.isBlank() || ".".equals(name)
                || "..".equals(name)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "detail", "Unsafe filename: " + name
                ));
            }
            Path target = directory.resolve(name);
            if (Files.isSymbolicLink(target)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "detail", "Unsafe filename: " + name
                ));
            }
            if (Files.exists(target)) {
                conflicts.add(name);
            }
            prepared.put(name, file);
        }
        if (!conflicts.isEmpty()
            && (conflict == null || conflict.isBlank())) {
            return ResponseEntity.status(409).body(Map.of(
                "detail", Map.of(
                    "code", "upload_conflict",
                    "files", conflicts
                )
            ));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, MultipartFile> entry
            : prepared.entrySet()) {
            String name = entry.getKey();
            Path target = directory.resolve(name);
            String status;
            if (Files.exists(target) && "skip".equals(conflict)) {
                status = "skipped";
            } else {
                if (Files.exists(target) && "rename".equals(conflict)) {
                    name = uniqueName(directory, name);
                    target = directory.resolve(name);
                }
                try {
                    Files.createDirectories(directory);
                    entry.getValue().transferTo(target.toFile());
                    status = "uploaded";
                } catch (IOException exception) {
                    return ResponseEntity.internalServerError().body(
                        Map.of("detail", "Failed to write "
                            + name + ": " + exception.getMessage())
                    );
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("path", directory.relativize(target)
                .toString().replace('\\', '/'));
            item.put("status", status);
            results.add(item);
        }
        return ResponseEntity.ok(Map.of("files", results));
    }

    private static ResponseEntity<Resource> download(Path workspace) {
        if (!Files.isDirectory(workspace)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] content = zipDirectory(workspace);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition",
                    "attachment; filename=\"workspace_"
                        + LocalDate.now() + ".zip\"")
                .body(new ByteArrayResource(content));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().build();
        }
    }

    static byte[] zipDirectory(Path root) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(buffer)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
                ) {
                    if (!directory.equals(root)
                        && SKIP_NAMES.contains(directory
                            .getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) throws IOException {
                    if (attrs.isSymbolicLink()
                        || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    output.putNextEntry(new ZipEntry(
                        root.relativize(file).toString()
                            .replace('\\', '/')
                    ));
                    Files.copy(file, output);
                    output.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return buffer.toByteArray();
    }

    static void extractAndMergeZip(
        MultipartFile file,
        Path workspace
    ) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(
            file.getInputStream()
        )) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException(
                        "Zip archive has too many entries"
                    );
                }
                String name = entry.getName();
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException(
                        "Zip contains unsafe path: " + name
                    );
                }
                rejectSymbolicLinks(root, target);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > MAX_EXPANDED_BYTES) {
                                throw new IllegalArgumentException(
                                    "Zip archive exceeds size limit"
                                );
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static Path resolveUploadDirectory(
        Path workspace,
        String path
    ) {
        Path root = workspace.toAbsolutePath().normalize();
        Path target = path == null || path.isBlank() ? root
            : root.resolve(path.replace('\\', '/')).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                "Unsafe upload path: " + path
            );
        }
        rejectSymbolicLinks(root, target);
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IllegalArgumentException(
                "Upload path is not a directory: " + path
            );
        }
        return target;
    }

    private static void rejectSymbolicLinks(
        Path root,
        Path target
    ) {
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) {
            throw new IllegalArgumentException(
                "Upload path crosses a symbolic link"
            );
        }
        for (Path component : root.relativize(target)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                    "Upload path crosses a symbolic link"
                );
            }
        }
    }

    private static String uniqueName(Path directory, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        String candidate = base + " (" + index + ")" + extension;
        while (Files.exists(directory.resolve(candidate))) {
            index++;
            candidate = base + " (" + index + ")" + extension;
        }
        return candidate;
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
