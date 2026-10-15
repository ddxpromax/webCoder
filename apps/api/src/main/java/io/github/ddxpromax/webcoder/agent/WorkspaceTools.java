package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** File and search tools scoped to one agent turn's workspace. */
public class WorkspaceTools {
    private final Path root;

    public WorkspaceTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Tool(
        name = "read_file",
        description = "Read a file with optional line offset and limit",
        readOnly = true,
        concurrencySafe = true
    )
    public String readFile(
        @ToolParam(name = "path", description = "File path")
        String path,
        @ToolParam(
            name = "offset",
            description = "Starting line offset",
            required = false
        ) Integer offset,
        @ToolParam(
            name = "limit",
            description = "Maximum lines",
            required = false
        ) Integer limit
    ) throws IOException {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            return "File not found: " + path;
        }
        if (Files.size(target) > 100_000) {
            return "File too large (" + Files.size(target)
                + " bytes)";
        }
        List<String> lines = Files.readAllLines(target);
        int start = offset == null ? 0 : Math.max(0, offset);
        if (start >= lines.size()) {
            return "Offset exceeds the file length";
        }
        int end = limit == null ? lines.size()
            : Math.min(lines.size(), start + Math.max(0, limit));
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            result.append(String.format(
                "%6d| %s%n", i + 1, lines.get(i)
            ));
        }
        return result.toString();
    }

    @Tool(name = "write_file", description = "Create or replace a file")
    public String writeFile(
        @ToolParam(name = "path", description = "File path")
        String path,
        @ToolParam(name = "content", description = "File content")
        String content
    ) throws IOException {
        Path target = resolve(path);
        Files.createDirectories(target.getParent());
        boolean existed = Files.exists(target);
        Files.writeString(target, content == null ? "" : content);
        return (existed ? "[updated] " : "[created] ")
            + path + " (" + Files.size(target) + " bytes)";
    }

    @Tool(
        name = "edit_file",
        description = "Replace one exact, unique text occurrence"
    )
    public String editFile(
        @ToolParam(name = "path", description = "File path")
        String path,
        @ToolParam(name = "oldString", description = "Original text")
        String oldString,
        @ToolParam(name = "newString", description = "Replacement text")
        String newString
    ) throws IOException {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            return "File not found: " + path;
        }
        if (oldString == null || oldString.isEmpty()) {
            return "oldString must not be empty";
        }
        String content = Files.readString(target);
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(oldString, index)) != -1) {
            count++;
            index += oldString.length();
        }
        if (count == 0) {
            return "oldString was not found";
        }
        if (count > 1) {
            return "oldString matched " + count
                + " locations; it must be unique";
        }
        Files.writeString(target,
            content.replace(oldString,
                newString == null ? "" : newString
            )
        );
        return "[edited] " + path;
    }

    @Tool(
        name = "append_file",
        description = "Append content or create a new file"
    )
    public String appendFile(
        @ToolParam(name = "path", description = "File path")
        String path,
        @ToolParam(name = "content", description = "Content to append")
        String content
    ) throws IOException {
        Path target = resolve(path);
        Files.createDirectories(target.getParent());
        boolean existed = Files.exists(target);
        Files.writeString(target,
            content == null ? "" : content,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
        return (existed ? "[appended] " : "[created] ")
            + path + " (" + Files.size(target) + " bytes)";
    }

    @Tool(
        name = "list_directory",
        description = "List directory contents",
        readOnly = true,
        concurrencySafe = true
    )
    public String listDirectory(
        @ToolParam(
            name = "path",
            description = "Directory path",
            required = false
        ) String path
    ) throws IOException {
        Path directory = path == null || path.isBlank()
            ? root : resolve(path);
        if (!Files.isDirectory(directory)) {
            return "Not a directory: " + directory;
        }
        StringBuilder result = new StringBuilder(
            directory.getFileName() + "/:\n"
        );
        try (var entries = Files.list(directory)) {
            entries.filter(entry -> {
                String name = entry.getFileName().toString();
                return !name.startsWith(".")
                    || ".gitignore".equals(name);
            }).sorted(Comparator.comparing(entry ->
                (Files.isDirectory(entry) ? "0_" : "1_")
                    + entry.getFileName()
            )).limit(80).forEach(entry -> result
                .append("  ")
                .append(entry.getFileName())
                .append(Files.isDirectory(entry) ? "/\n" : "\n")
            );
        }
        return result.toString();
    }

    @Tool(
        name = "search_code",
        description = "Search source files for a keyword",
        readOnly = true,
        concurrencySafe = true
    )
    public String searchCode(
        @ToolParam(name = "pattern", description = "Keyword")
        String pattern,
        @ToolParam(
            name = "filePattern",
            description = "Optional filename filter",
            required = false
        ) String filePattern,
        @ToolParam(
            name = "maxResults",
            description = "Maximum results",
            required = false
        ) Integer maxResults
    ) throws IOException {
        Pattern query = Pattern.compile(Pattern.quote(pattern),
            Pattern.CASE_INSENSITIVE);
        int limit = maxResults == null ? 20
            : Math.max(1, Math.min(maxResults, 50));
        List<String> results = new ArrayList<>();
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
                ) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = directory.getFileName()
                        .toString();
                    return name.startsWith(".")
                        || "target".equals(name)
                        || "node_modules".equals(name)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) {
                    if (results.size() >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    String name = file.getFileName().toString();
                    if (!attrs.isRegularFile()
                        || attrs.size() > 1_000_000
                        || name.startsWith(".")
                        || !matchesFilePattern(name, filePattern)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size()
                            && results.size() < limit; i++) {
                            if (query.matcher(lines.get(i)).find()) {
                                results.add(root.relativize(file)
                                    + ":" + (i + 1) + "  "
                                    + lines.get(i).trim());
                            }
                        }
                    } catch (IOException ignored) {
                        // Unreadable and binary files are skipped.
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return results.isEmpty()
            ? "No matches for '" + pattern + "'"
            : "Found " + results.size() + " matches:\n"
                + String.join("\n", results);
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                "Path is outside the workspace: " + path
            );
        }
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) {
            throw new IllegalArgumentException(
                "Path crosses a symbolic link: " + path
            );
        }
        for (Path component : root.relativize(target)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                    "Path crosses a symbolic link: " + path
                );
            }
        }
        return target;
    }

    private static boolean matchesFilePattern(
        String name,
        String pattern
    ) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String suffix = pattern.replace("*", "")
            .toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }
}
