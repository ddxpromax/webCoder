package io.github.ddxpromax.webcoder.skill;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Workspace skills use Majo's skills/name/SKILL.md and skill.json layout. */
@Service
public class SkillWorkspaceService {
    private static final Pattern NAME = Pattern.compile(
        "[A-Za-z0-9_-]+"
    );
    private final JsonFileStore files;

    public SkillWorkspaceService(JsonFileStore files) {
        this.files = files;
    }

    public synchronized List<Map<String, Object>> list(Path workspace) {
        Path root = workspaceRoot(workspace);
        reconcile(root);
        Map<String, Object> entries = entries(readManifest(root));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : entries.keySet().stream().sorted().toList()) {
            Path dir = skillDirectory(root, name);
            if (!Files.isRegularFile(dir.resolve("SKILL.md"))) {
                continue;
            }
            Map<String, Object> entry = asMap(entries.get(name));
            String content = readText(dir.resolve("SKILL.md"));
            Map<String, Object> frontmatter = parseFrontmatter(content);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", name);
            spec.put("description", str(frontmatter.get("description")));
            spec.put("version_text", version(frontmatter));
            spec.put("content", content);
            spec.put("source", str(entry.getOrDefault("source", "customized")));
            spec.put("enabled", Boolean.TRUE.equals(entry.get("enabled")));
            spec.put("channels", entry.getOrDefault("channels", List.of("all")));
            spec.put("preload", Boolean.TRUE.equals(entry.get("preload")));
            spec.put("tags", entry.getOrDefault("tags", List.of()));
            spec.put("config", entry.getOrDefault("config", Map.of()));
            spec.put("last_updated", lastUpdated(dir.resolve("SKILL.md")));
            spec.put("emoji", str(frontmatter.get("emoji")));
            spec.put("installed_from", str(entry.get("installed_from")));
            spec.put("requirements", frontmatter.get("requires"));
            result.add(spec);
        }
        return result;
    }

    public synchronized List<String> enabledNames(Path workspace) {
        Path root = workspaceRoot(workspace);
        reconcile(root);
        return entries(readManifest(root)).entrySet().stream()
            .filter(entry -> Boolean.TRUE.equals(
                asMap(entry.getValue()).get("enabled")
            ))
            .map(Map.Entry::getKey)
            .filter(name -> Files.isRegularFile(
                skillDirectory(root, name).resolve("SKILL.md")
            ))
            .sorted()
            .toList();
    }

    public synchronized void reconcile(Path workspace) {
        Path root = workspaceRoot(workspace);
        Path skillsDir = root.resolve("skills");
        try {
            Files.createDirectories(skillsDir);
            Map<String, Object> manifest = readManifest(root);
            Map<String, Object> entries = entries(manifest);
            boolean changed = false;
            try (var paths = Files.list(skillsDir)) {
                for (Path dir : paths.sorted().toList()) {
                    String name = dir.getFileName().toString();
                    if (!NAME.matcher(name).matches()
                        || Files.isSymbolicLink(dir)
                        || !Files.isDirectory(dir)
                        || !Files.isRegularFile(dir.resolve("SKILL.md"),
                            LinkOption.NOFOLLOW_LINKS)
                        || entries.containsKey(name)) {
                        continue;
                    }
                    Map<String, Object> entry = defaultEntry();
                    entries.put(name, entry);
                    changed = true;
                }
            }
            if (changed || !Files.isRegularFile(manifestPath(root))) {
                files.writeAtomic(manifestPath(root), manifest);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not reconcile workspace skills", exception
            );
        }
    }

    public synchronized Map<String, Object> create(
        Path workspace,
        String name,
        String content,
        boolean enabled
    ) {
        String skillName = skillName(name);
        validateContent(content);
        ensureSafe(skillName, content);
        Path root = workspaceRoot(workspace);
        reconcile(root);
        Path directory = skillDirectory(root, skillName);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of("success", false, "reason", "conflict");
        }
        Map<String, Object> manifest = readManifest(root);
        try {
            Files.createDirectory(directory);
            Files.writeString(
                directory.resolve("SKILL.md"), content,
                StandardCharsets.UTF_8
            );
            Map<String, Object> entry = defaultEntry();
            entry.put("enabled", enabled);
            entries(manifest).put(skillName, entry);
            files.writeAtomic(manifestPath(root), manifest);
            return Map.of("success", true, "name", skillName);
        } catch (Exception exception) {
            deleteTree(directory);
            throw new IllegalStateException(
                "Could not create workspace skill", exception
            );
        }
    }

    public synchronized Map<String, Object> save(
        Path workspace,
        String sourceName,
        String targetName,
        String content,
        Map<String, Object> config,
        boolean overwrite
    ) {
        String source = skillName(sourceName);
        String target = skillName(targetName == null || targetName.isBlank()
            ? source : targetName);
        validateContent(content);
        ensureSafe(target, content);
        Path root = workspaceRoot(workspace);
        reconcile(root);
        Map<String, Object> manifest = readManifest(root);
        Map<String, Object> entries = entries(manifest);
        if (!entries.containsKey(source)) {
            return Map.of("success", false, "reason", "not_found");
        }
        Map<String, Object> entry = asMap(entries.get(source));
        Path oldDir = skillDirectory(root, source);
        Path newDir = skillDirectory(root, target);
        if (!source.equals(target) && Files.exists(newDir)
            && !overwrite) {
            return Map.of("success", false, "reason", "conflict");
        }
        try {
            if (!source.equals(target)) {
                if (Files.exists(newDir)) {
                    deleteTree(newDir);
                }
                Files.move(oldDir, newDir);
                entries.remove(source);
            }
            Path temporary = Files.createTempFile(
                newDir, "SKILL", ".tmp"
            );
            try {
                Files.writeString(temporary, content,
                    StandardCharsets.UTF_8);
                Files.move(temporary, newDir.resolve("SKILL.md"),
                    StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (config != null) {
                entry.put("config", new LinkedHashMap<>(config));
            }
            entries.put(target, entry);
            files.writeAtomic(manifestPath(root), manifest);
            return Map.of("success", true, "name", target);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not save workspace skill", exception
            );
        }
    }

    public synchronized boolean setEnabled(
        Path workspace,
        String name,
        boolean enabled
    ) {
        if (enabled) {
            String skillName = skillName(name);
            Path file = skillDirectory(
                workspaceRoot(workspace), skillName
            ).resolve("SKILL.md");
            if (!Files.isRegularFile(file)) {
                return false;
            }
            ensureSafe(skillName, readText(file));
        }
        return updateEntry(workspace, name, entry ->
            entry.put("enabled", enabled)
        );
    }

    public synchronized boolean setChannels(
        Path workspace,
        String name,
        List<String> channels
    ) {
        return updateEntry(workspace, name, entry ->
            entry.put("channels", channels == null
                ? List.of("all") : List.copyOf(channels))
        );
    }

    public synchronized boolean setPreload(
        Path workspace,
        String name,
        boolean preload
    ) {
        return updateEntry(workspace, name, entry ->
            entry.put("preload", preload)
        );
    }

    public synchronized boolean setTags(
        Path workspace,
        String name,
        List<String> tags
    ) {
        List<String> cleaned = tags == null ? List.of()
            : tags.stream().map(String::trim).distinct().toList();
        if (cleaned.size() > 8 || cleaned.stream().anyMatch(
            tag -> tag.isEmpty() || tag.length() > 16)) {
            throw new IllegalArgumentException(
                "Use at most eight tags of 1–16 characters"
            );
        }
        return updateEntry(workspace, name, entry ->
            entry.put("tags", cleaned)
        );
    }

    public synchronized boolean setConfig(
        Path workspace,
        String name,
        Map<String, Object> config
    ) {
        return updateEntry(workspace, name, entry ->
            entry.put("config", config == null
                ? Map.of() : new LinkedHashMap<>(config))
        );
    }

    public synchronized Map<String, Object> config(
        Path workspace,
        String name
    ) {
        reconcile(workspace);
        Map<String, Object> entry = asMap(
            entries(readManifest(workspaceRoot(workspace)))
                .get(skillName(name))
        );
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("Skill not found");
        }
        return Map.of("config", entry.getOrDefault(
            "config", Map.of()
        ));
    }

    public synchronized boolean delete(Path workspace, String name) {
        Path root = workspaceRoot(workspace);
        String normalized = skillName(name);
        Map<String, Object> manifest = readManifest(root);
        Map<String, Object> entries = entries(manifest);
        Map<String, Object> entry = asMap(entries.get(normalized));
        if (entry.isEmpty() || Boolean.TRUE.equals(entry.get("enabled"))) {
            return false;
        }
        deleteTree(skillDirectory(root, normalized));
        entries.remove(normalized);
        files.writeAtomic(manifestPath(root), manifest);
        return true;
    }

    public String loadFile(
        Path workspace,
        String name,
        String path
    ) {
        String normalized = Objects.requireNonNull(path)
            .replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")
            || !(normalized.startsWith("references/")
                || normalized.startsWith("scripts/"))) {
            throw new IllegalArgumentException("Invalid skill file path");
        }
        Path directory = skillDirectory(
            workspaceRoot(workspace), skillName(name)
        );
        Path target = directory.resolve(normalized).normalize();
        if (!target.startsWith(directory)
            || Files.isSymbolicLink(target)
            || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Skill file not found");
        }
        return readText(target);
    }

    public static Map<String, Object> parseFrontmatter(String content) {
        String text = Objects.requireNonNull(content);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---\n")) {
            return Map.of();
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            return Map.of();
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options))
            .load(text.substring(4, end));
        if (!(parsed instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(
            String.valueOf(key), value
        ));
        return result;
    }

    public static void validateContent(String content) {
        Map<String, Object> frontmatter;
        try {
            frontmatter = parseFrontmatter(content);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "SKILL.md frontmatter is not valid YAML", exception
            );
        }
        if (str(frontmatter.get("name")).isBlank()
            || str(frontmatter.get("description")).isBlank()) {
            throw new IllegalArgumentException(
                "SKILL.md requires a name and description"
            );
        }
        Object metadata = frontmatter.get("metadata");
        if (metadata != null && !(metadata instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                "SKILL.md metadata must be a map"
            );
        }
    }

    private static void ensureSafe(String name, String content) {
        var scan = SkillSecurityScanner.scan(
            name, content, Map.of()
        );
        if (scan.verdict()
            == SkillSecurityScanner.Verdict.DANGEROUS) {
            throw new IllegalArgumentException(
                "Skill security scan failed: " + scan.reportText()
            );
        }
    }

    private boolean updateEntry(
        Path workspace,
        String name,
        java.util.function.Consumer<Map<String, Object>> change
    ) {
        Path root = workspaceRoot(workspace);
        reconcile(root);
        Map<String, Object> manifest = readManifest(root);
        Map<String, Object> entries = entries(manifest);
        String normalized = skillName(name);
        Map<String, Object> entry = asMap(entries.get(normalized));
        if (entry.isEmpty()) {
            return false;
        }
        change.accept(entry);
        entries.put(normalized, entry);
        files.writeAtomic(manifestPath(root), manifest);
        return true;
    }

    private Map<String, Object> readManifest(Path root) {
        Map<String, Object> manifest = files.read(
            manifestPath(root), defaultManifest()
        );
        manifest.computeIfAbsent("skills", key ->
            new LinkedHashMap<String, Object>()
        );
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entries(Map<String, Object> manifest) {
        return (Map<String, Object>) manifest.get("skills");
    }

    private static Map<String, Object> defaultManifest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "workspace-skill-manifest.v1");
        result.put("version", 0);
        result.put("skills", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Map<String, Object> defaultEntry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", false);
        result.put("channels", List.of("all"));
        result.put("source", "customized");
        result.put("installed_from", "");
        result.put("config", Map.of());
        result.put("tags", List.of());
        result.put("preload", false);
        return result;
    }

    private static String version(Map<String, Object> frontmatter) {
        Object direct = frontmatter.get("version");
        if (direct != null) {
            return str(direct);
        }
        return str(asMap(frontmatter.get("metadata"))
            .get("version"));
    }

    private static String lastUpdated(Path path) {
        try {
            return Instant.ofEpochMilli(
                Files.getLastModifiedTime(path).toMillis()
            ).toString();
        } catch (IOException exception) {
            return "";
        }
    }

    private static Path workspaceRoot(Path workspace) {
        return workspace.toAbsolutePath().normalize();
    }

    private static Path manifestPath(Path root) {
        return root.resolve("skill.json");
    }

    private static Path skillDirectory(Path root, String name) {
        Path directory = root.resolve("skills").resolve(name);
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("Skill directory is a link");
        }
        return directory;
    }

    private static String skillName(String name) {
        String trimmed = Objects.requireNonNull(name).trim();
        if (!NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid skill name");
        }
        return trimmed;
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not read skill file", exception
            );
        }
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(
                Comparator.reverseOrder()
            ).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not delete skill files", exception
            );
        }
    }

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(
            String.valueOf(key), value
        ));
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
