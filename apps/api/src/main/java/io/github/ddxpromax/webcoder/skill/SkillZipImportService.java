package io.github.ddxpromax.webcoder.skill;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/** Validates and imports Majo's single- or multi-skill ZIP bundles. */
@Service
public class SkillZipImportService {
    private static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
    private final SkillWorkspaceService skills;

    public SkillZipImportService(SkillWorkspaceService skills) {
        this.skills = skills;
    }

    public Map<String, Object> importZip(
        Path workspace,
        byte[] archive,
        boolean enabled,
        String targetName,
        Map<String, String> renameMap
    ) {
        Path staging = null;
        List<String> imported = new ArrayList<>();
        try {
            staging = Files.createTempDirectory("majo-skill-upload-");
            extract(archive, staging);
            List<Path> candidates = findSkills(staging);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "No valid skills found in uploaded ZIP"
                );
            }
            if (targetName != null && !targetName.isBlank()
                && candidates.size() != 1) {
                throw new IllegalArgumentException(
                    "target_name requires a single-skill ZIP"
                );
            }
            List<Candidate> planned = new ArrayList<>();
            List<Map<String, Object>> conflicts = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Path candidate : candidates) {
                String content = Files.readString(
                    candidate.resolve("SKILL.md"), StandardCharsets.UTF_8
                );
                SkillWorkspaceService.validateContent(content);
                Map<String, Object> frontmatter =
                    SkillWorkspaceService.parseFrontmatter(content);
                String sourceName = String.valueOf(
                    frontmatter.get("name")
                );
                String selected = targetName == null
                    || targetName.isBlank()
                    ? sourceName : targetName;
                selected = renameMap == null
                    ? selected : renameMap.getOrDefault(
                        selected, selected
                    );
                if (!selected.matches("[A-Za-z0-9_-]+")) {
                    throw new IllegalArgumentException(
                        "Invalid skill name: " + selected
                    );
                }
                ensureSafe(selected, candidate, content);
                if (!names.add(selected) || Files.exists(
                    workspace.resolve("skills").resolve(selected)
                )) {
                    conflicts.add(Map.of(
                        "name", selected,
                        "reason", "conflict"
                    ));
                } else {
                    planned.add(new Candidate(
                        candidate, selected, content
                    ));
                }
            }
            if (!conflicts.isEmpty()) {
                return result(List.of(), false, conflicts);
            }

            for (Candidate candidate : planned) {
                Map<String, Object> created = skills.create(
                    workspace, candidate.name(),
                    candidate.content(), false
                );
                if (!Boolean.TRUE.equals(created.get("success"))) {
                    throw new IllegalStateException(
                        "Skill appeared during import: "
                            + candidate.name()
                    );
                }
                imported.add(candidate.name());
                Path target = workspace.resolve("skills")
                    .resolve(candidate.name());
                copyAuxiliaryFiles(candidate.directory(), target);
                skills.setInstalledFrom(
                    workspace, candidate.name(), "zip"
                );
            }
            if (enabled) {
                for (String name : imported) {
                    skills.setEnabled(workspace, name, true);
                }
            }
            return result(imported, enabled, List.of());
        } catch (IOException exception) {
            rollback(workspace, imported);
            throw new IllegalStateException(
                "Could not import skill ZIP", exception
            );
        } catch (RuntimeException exception) {
            rollback(workspace, imported);
            throw exception;
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    private static Map<String, Object> result(
        List<String> names,
        boolean enabled,
        List<Map<String, Object>> conflicts
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", names);
        result.put("count", names.size());
        result.put("enabled", enabled && !names.isEmpty());
        result.put("conflicts", conflicts);
        return result;
    }

    private static void extract(byte[] archive, Path root)
        throws IOException {
        if (archive == null || archive.length == 0) {
            throw new IllegalArgumentException(
                "Uploaded file is not a valid ZIP archive"
            );
        }
        long written = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(
            new ByteArrayInputStream(archive)
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                Path target = root.resolve(entry.getName())
                    .toAbsolutePath().normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException(
                        "Unsafe path in ZIP: " + entry.getName()
                    );
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = zip.read(buffer)) != -1) {
                        written += length;
                        if (written > MAX_UNCOMPRESSED) {
                            throw new IllegalArgumentException(
                                "Uncompressed ZIP exceeds 200 MB"
                            );
                        }
                        output.write(buffer, 0, length);
                    }
                }
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                "Uploaded file is not a valid ZIP archive"
            );
        }
    }

    private static List<Path> findSkills(Path root)
        throws IOException {
        List<Path> top = children(root);
        Path actual = top.size() == 1 && Files.isDirectory(top.getFirst())
            ? top.getFirst() : root;
        if (Files.isRegularFile(actual.resolve("SKILL.md"))) {
            return List.of(actual);
        }
        return children(actual).stream()
            .filter(Files::isDirectory)
            .filter(path -> Files.isRegularFile(
                path.resolve("SKILL.md")
            ))
            .toList();
    }

    private static List<Path> children(Path directory)
        throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> {
                String name = path.getFileName().toString();
                return !name.equals("__MACOSX")
                    && !name.equals(".DS_Store");
            }).sorted().toList();
        }
    }

    private static void ensureSafe(
        String name,
        Path directory,
        String content
    ) throws IOException {
        Map<String, String> extra = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .toList()) {
                String relative = directory.relativize(path)
                    .toString().replace('\\', '/');
                if (!relative.equals("SKILL.md")) {
                    try {
                        extra.put(relative, Files.readString(
                            path, StandardCharsets.UTF_8
                        ));
                    } catch (java.nio.charset.MalformedInputException ignored) {
                        // Non-text resources cannot contain executable text.
                    }
                }
            }
        }
        var scan = SkillSecurityScanner.scan(
            name, content, extra
        );
        if (scan.verdict()
            == SkillSecurityScanner.Verdict.DANGEROUS) {
            throw new IllegalArgumentException(
                "Skill security scan failed: " + scan.reportText()
            );
        }
    }

    private static void copyAuxiliaryFiles(
        Path source,
        Path target
    ) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()
                    || relative.toString().equals("SKILL.md")) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination,
                        StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void rollback(Path workspace, List<String> names) {
        for (String name : names) {
            try {
                skills.setEnabled(workspace, name, false);
                skills.delete(workspace, name);
            } catch (Exception ignored) {
                // Preserve the original import failure.
            }
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(
                Comparator.reverseOrder()
            ).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The temporary extraction directory is disposable.
        }
    }

    private record Candidate(
        Path directory,
        String name,
        String content
    ) {}
}
