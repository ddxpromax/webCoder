package io.github.ddxpromax.webcoder.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.jgit.api.Git;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspace/coding-project")
@CrossOrigin(origins = "*")
public class WorkspaceProjectController {
    private static final Set<String> COPY_SKIP_DIRS = Set.of(
        ".git", "__pycache__", ".venv", "node_modules",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
        ".hypothesis", "target", ".idea", ".next", "dist"
    );
    private static final Set<String> SENSITIVE_NAMES = Set.of(
        ".ssh", ".aws", ".gnupg", ".kube", ".docker",
        ".azure", ".claude", ".password-store", ".env",
        ".netrc", ".npmrc", ".yarnrc", ".pypirc",
        ".gitconfig", ".git-credentials", ".terraformrc",
        ".vault-token"
    );
    private static final List<List<String>> SENSITIVE_SEQUENCES =
        List.of(
            List.of(".config", "gcloud"),
            List.of(".config", "nix"),
            List.of(".config", "gh"),
            List.of("library", "keychains"),
            List.of("library", "cookies"),
            List.of("library", "application support", "google", "chrome"),
            List.of("library", "application support", "firefox"),
            List.of("appdata", "roaming", "gcloud"),
            List.of("appdata", "roaming", "github cli"),
            List.of("appdata", "local", "google", "chrome", "user data"),
            List.of("appdata", "roaming", "mozilla", "firefox")
        );
    private static final long MAX_ZIP_EXPANDED_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;

    private final AgentStore agents;

    public WorkspaceProjectController(AgentStore agents) {
        this.agents = agents;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Path base = agents.defaultWorkspaceDir()
            .resolve("coding_projects");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (var entries = Files.list(base)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", dir.toAbsolutePath().toString());
                item.put("name", dir.getFileName().toString());
                item.put("is_git", Files.isDirectory(dir.resolve(".git")));
                item.put("is_active", false);
                result.add(item);
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to list coding projects", exception
            );
        }
        return result;
    }

    @GetMapping
    public Map<String, Object> current(HttpServletRequest request) {
        Path workspace = workspace(request);
        String selected = agents.getProjectDir(agentId(request));
        boolean isDefault = selected == null || selected.isBlank()
            || workspace.equals(Path.of(selected).toAbsolutePath()
                .normalize());
        Path effective = isDefault ? workspace
            : Path.of(selected).toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", effective.toString());
        result.put("name", effective.getFileName().toString());
        result.put("is_workspace_default", isDefault);
        result.put("workspace_dir", workspace.toString());
        result.put("exists", Files.exists(effective));
        return result;
    }

    @GetMapping("/dirs")
    public List<Map<String, String>> dirs(HttpServletRequest request) {
        return agents.getProjectDirs(agentId(request));
    }

    @PutMapping("/dirs")
    public List<Map<String, String>> replaceDirs(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        List<Map<String, String>> dirs = new ArrayList<>();
        if (body.get("project_dirs") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    dirs.add(Map.of(
                        "path", text(entry.get("path")),
                        "label", text(entry.get("label"))
                    ));
                }
            }
        }
        agents.setProjectDirs(agentId(request), dirs);
        return agents.getProjectDirs(agentId(request));
    }

    @DeleteMapping("/dirs")
    public List<Map<String, String>> clearDirs(
        HttpServletRequest request
    ) {
        agents.setProjectDirs(agentId(request), List.of());
        return agents.getProjectDirs(agentId(request));
    }

    @GetMapping("/browse-dirs")
    public Map<String, Object> browseDirs(
        @RequestParam(defaultValue = "~") String path,
        @RequestParam(defaultValue = "false") boolean show_hidden
    ) {
        Path current = "~".equals(path)
            ? Path.of(System.getProperty("user.home"))
            : Path.of(path).toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", current.toString());
        result.put("parent", current.getParent() == null
            ? null : current.getParent().toString());
        List<Map<String, Object>> dirs = new ArrayList<>();
        if (Files.isDirectory(current)) {
            try (var entries = Files.list(current)) {
                entries.filter(Files::isDirectory)
                    .filter(dir -> show_hidden || !dir.getFileName()
                        .toString().startsWith("."))
                    .forEach(dir -> dirs.add(Map.of(
                        "name", dir.getFileName().toString(),
                        "path", dir.toAbsolutePath().toString()
                    )));
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Failed to browse directories", exception
                );
            }
        }
        dirs.sort(Comparator.comparing(item ->
            String.valueOf(item.get("name"))
        ));
        result.put("dirs", dirs);
        return result;
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> select(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String input = text(body.get("path"));
        if (input.isBlank()) {
            agents.setProjectDir(agentId(request), null);
            Path workspace = workspace(request);
            return ResponseEntity.ok(Map.of(
                "path", workspace.toString(),
                "name", workspace.getFileName().toString(),
                "is_workspace_default", true
            ));
        }
        Path target = Path.of(input).toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Path does not exist: " + target
            ));
        }
        agents.setProjectDir(agentId(request), target.toString());
        return ResponseEntity.ok(Map.of(
            "path", target.toString(),
            "name", target.getFileName().toString(),
            "is_workspace_default", false,
            "workspace_dir", target.toString(),
            "exists", true
        ));
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String name = text(body.get("name")).trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Project name cannot be empty"
            ));
        }
        Path destination;
        try {
            Path base = projectBase(request);
            Files.createDirectories(base);
            destination = safeDestination(base, name);
            Files.createDirectories(destination);
            try (Git ignored = Git.init()
                .setDirectory(destination.toFile()).call()) {
                // The repository is initialized by JGit.
            }
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        } catch (Exception exception) {
            return serverError(exception);
        }
        agents.setProjectDir(agentId(request), destination.toString());
        return ResponseEntity.ok(projectIdentity(destination));
    }

    @PostMapping("/clone")
    public ResponseEntity<Map<String, Object>> cloneProject(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String url = text(body.get("url"));
        if (url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "url is required"
            ));
        }
        try {
            Path base = projectBase(request);
            Files.createDirectories(base);
            String name = text(body.get("name")).trim();
            Path destination = name.isBlank()
                ? base : safeDestination(base, name);
            try (Git ignored = Git.cloneRepository()
                .setURI(url)
                .setDirectory(destination.toFile())
                .call()) {
                // JGit performs the clone before the project is selected.
            }
            agents.setProjectDir(agentId(request), destination.toString());
            return ResponseEntity.ok(projectIdentity(destination));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        } catch (Exception exception) {
            return serverError(exception);
        }
    }

    @PostMapping("/import-local")
    public ResponseEntity<Map<String, Object>> importLocal(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String input = text(body.get("path"));
        if (input.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "path is required"
            ));
        }
        Path source = Path.of(input).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Not a directory: " + source
            ));
        }
        try {
            source = source.toRealPath();
        } catch (IOException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Not a directory: " + source
            ));
        }
        String rejection = validateImportSource(source);
        if (rejection != null) {
            return ResponseEntity.status(403).body(Map.of(
                "detail", rejection
            ));
        }
        String requestedName = text(body.get("name")).trim();
        String name = requestedName.isEmpty()
            ? source.getFileName().toString() : requestedName;
        try {
            Path base = projectBase(request);
            Files.createDirectories(base);
            Path destination = safeDestination(base, name);
            copyDirectoryExcluding(source, destination);
            agents.setProjectDir(agentId(request), destination.toString());
            return ResponseEntity.ok(projectIdentity(destination));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        } catch (Exception exception) {
            return serverError(exception);
        }
    }

    @PostMapping("/upload-zip")
    public ResponseEntity<Map<String, Object>> uploadZip(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String name,
        HttpServletRequest request
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "file is required"
            ));
        }
        try {
            Path base = projectBase(request);
            Files.createDirectories(base);
            Path destination = safeDestination(base,
                name == null || name.isBlank() ? "project" : name.trim()
            );
            Files.createDirectories(destination);
            extractZip(file, destination);
            agents.setProjectDir(agentId(request), destination.toString());
            return ResponseEntity.ok(projectIdentity(destination));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", exception.getMessage()
            ));
        } catch (Exception exception) {
            return serverError(exception);
        }
    }

    private Path workspace(HttpServletRequest request) {
        return agents.workspaceDirForAgent(agentId(request));
    }

    private Path projectBase(HttpServletRequest request) {
        return workspace(request).resolve("coding_projects");
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

    private static Path safeDestination(Path base, String name) {
        Path target = base.resolve(name).normalize();
        if (name.isBlank() || target.equals(base)
            || !target.startsWith(base)) {
            throw new IllegalArgumentException(
                "Unsafe project name: " + name
            );
        }
        return target;
    }

    private static Map<String, Object> projectIdentity(Path path) {
        return Map.of(
            "path", path.toString(),
            "name", path.getFileName().toString()
        );
    }

    private static ResponseEntity<Map<String, Object>> serverError(
        Exception exception
    ) {
        return ResponseEntity.internalServerError().body(Map.of(
            "detail", exception.getMessage() == null
                ? "Project operation failed" : exception.getMessage()
        ));
    }

    private static String validateImportSource(Path source) {
        Path home = Path.of(System.getProperty("user.home"))
            .toAbsolutePath().normalize();
        if (!source.startsWith(home)) {
            return "Source must be under home directory: " + home;
        }
        if (source.equals(home)) {
            return "Cannot import the entire home directory";
        }
        List<String> parts = new ArrayList<>();
        home.relativize(source).forEach(part -> parts.add(
            part.toString().toLowerCase(Locale.ROOT)
        ));
        for (String part : parts) {
            if (SENSITIVE_NAMES.contains(part)) {
                return "Path contains sensitive component: " + part;
            }
        }
        if (hasSensitiveSequence(parts)) {
            return "Path contains sensitive directory sequence";
        }
        return null;
    }

    private static boolean hasSensitiveSequence(List<String> parts) {
        for (List<String> sequence : SENSITIVE_SEQUENCES) {
            for (int i = 0; i + sequence.size() <= parts.size(); i++) {
                if (parts.subList(i, i + sequence.size())
                    .equals(sequence)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void copyDirectoryExcluding(
        Path source,
        Path destination
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                Path dir,
                BasicFileAttributes attrs
            ) throws IOException {
                if (attrs.isSymbolicLink()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                List<String> parts = lowerParts(source.relativize(dir));
                if (!dir.equals(source) && (
                    COPY_SKIP_DIRS.contains(dir.getFileName().toString())
                    || SENSITIVE_NAMES.contains(parts.getLast())
                    || hasSensitiveSequence(parts)
                )) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(
                    destination.resolve(source.relativize(dir))
                );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attrs
            ) throws IOException {
                List<String> parts = lowerParts(
                    source.relativize(file)
                );
                if (attrs.isSymbolicLink()
                    || SENSITIVE_NAMES.contains(parts.getLast())
                    || hasSensitiveSequence(parts)) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = destination.resolve(
                    source.relativize(file)
                );
                Files.createDirectories(target.getParent());
                Files.copy(file, target,
                    StandardCopyOption.REPLACE_EXISTING
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<String> lowerParts(Path path) {
        List<String> parts = new ArrayList<>();
        path.forEach(part -> parts.add(
            part.toString().toLowerCase(Locale.ROOT)
        ));
        return parts;
    }

    private static void extractZip(
        MultipartFile file,
        Path destination
    ) throws IOException {
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
                Path target = destination.resolve(entry.getName())
                    .normalize();
                if (!target.startsWith(destination)) {
                    throw new IllegalArgumentException(
                        "Unsafe zip entry: " + entry.getName()
                    );
                }
                rejectSymbolicLinkPath(destination, target);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > MAX_ZIP_EXPANDED_BYTES) {
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

    private static void rejectSymbolicLinkPath(
        Path destination,
        Path target
    ) {
        Path cursor = destination;
        if (Files.isSymbolicLink(cursor)) {
            throw new IllegalArgumentException(
                "Zip destination cannot be a symbolic link"
            );
        }
        for (Path part : destination.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                    "Zip entry crosses a symbolic link"
                );
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
