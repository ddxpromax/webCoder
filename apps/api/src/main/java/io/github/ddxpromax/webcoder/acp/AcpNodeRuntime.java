package io.github.ddxpromax.webcoder.acp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Majo-compatible detection and validation for the Node runtime used by ACP. */
public final class AcpNodeRuntime {
    private static final String DESKTOP_RUNTIME = "MAJO_DESKTOP_NODE_RUNTIME";

    private AcpNodeRuntime() { }

    public static Map<String, Object> status(String configuredPath) {
        String configured = configuredPath == null ? "" : configuredPath.trim();
        List<Map<String, Object>> candidates = new ArrayList<>();
        String bundled = System.getenv(DESKTOP_RUNTIME);
        if (bundled != null && !bundled.isBlank()) {
            candidates.add(resolve(bundled, "bundled", "bundled"));
        }
        String system = which("node", null);
        candidates.add(system == null
            ? missing("system", "system", "system_node_missing", "System Node was not detected")
            : resolve(system, "system", "system"));
        if (!configured.isBlank() && candidates.stream().noneMatch(candidate -> samePath(
            configured, string(candidate.get("node_path"))))) {
            candidates.add(resolve(configured, "custom", "custom"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_path", configured);
        result.put("effective_node_path", effective(configured, candidates));
        result.put("candidates", candidates);
        return result;
    }

    public static Map<String, Object> resolve(String path) {
        return resolve(path, "custom", "custom");
    }

    private static Map<String, Object> resolve(String rawPath, String key, String label) {
        Path node = normalize(rawPath);
        Map<String, Object> result = candidate(key, label, node.toString());
        if (!Files.isRegularFile(node)) {
            result.put("reason_code", "node_missing");
            result.put("reason", "Node path does not exist");
            return result;
        }
        Map<String, String> environment = withPath(node.getParent());
        Version nodeVersion = version(node, environment);
        if (!nodeVersion.error().isBlank()) {
            result.put("reason_code", "version_check_failed");
            result.put("reason", nodeVersion.error());
            return result;
        }
        Path npx = npxPath(node);
        result.put("node_version", nodeVersion.value());
        if (npx == null) {
            result.put("reason_code", "npx_missing");
            result.put("reason", "npx was not found");
            return result;
        }
        Version npxVersion = version(npx, environment);
        result.put("npx_path", npx.toString());
        if (!npxVersion.error().isBlank()) {
            result.put("reason_code", "version_check_failed");
            result.put("reason", npxVersion.error());
            return result;
        }
        result.put("npx_version", npxVersion.value());
        result.put("available", true);
        return result;
    }

    private static Map<String, Object> candidate(String key, String label, String nodePath) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("label", label);
        result.put("node_path", nodePath);
        result.put("npx_path", "");
        result.put("node_version", "");
        result.put("npx_version", "");
        result.put("available", false);
        result.put("reason_code", "");
        result.put("reason", "");
        return result;
    }

    private static Map<String, Object> missing(String key, String label, String code, String reason) {
        Map<String, Object> result = candidate(key, label, "");
        result.put("reason_code", code);
        result.put("reason", reason);
        return result;
    }

    private static String effective(String configured, List<Map<String, Object>> candidates) {
        if (!configured.isBlank()) {
            for (Map<String, Object> candidate : candidates) {
                if (Boolean.TRUE.equals(candidate.get("available")) && samePath(configured,
                    string(candidate.get("node_path")))) return string(candidate.get("node_path"));
            }
        }
        for (String key : List.of("bundled", "system")) {
            for (Map<String, Object> candidate : candidates) {
                if (key.equals(candidate.get("key")) && Boolean.TRUE.equals(candidate.get("available"))) {
                    return string(candidate.get("node_path"));
                }
            }
        }
        return "";
    }

    private static Path normalize(String rawPath) {
        Path value = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        if (Files.isDirectory(value)) value = value.resolve(isWindows() ? "node.exe" : "bin/node");
        return value;
    }

    private static Path npxPath(Path node) {
        Path directory = node.getParent() == null ? Path.of(".") : node.getParent();
        for (String name : isWindows() ? List.of("npx.cmd", "npx.exe") : List.of("npx")) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        String found = which("npx", directory.toString());
        return found == null ? null : Path.of(found).toAbsolutePath().normalize();
    }

    private static Version version(Path executable, Map<String, String> environment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--version")
                .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            process.getOutputStream().close();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!completed) {
                process.destroyForcibly();
                return new Version("", "version check failed: timeout");
            }
            if (process.exitValue() != 0) return new Version("", output.isBlank() ? "version check failed" : output);
            return new Version(output, "");
        } catch (Exception exception) {
            return new Version("", executable.getFileName() + " --version failed: " + exception.getMessage());
        }
    }

    private static Map<String, String> withPath(Path directory) {
        Map<String, String> result = new LinkedHashMap<>(System.getenv());
        String key = result.keySet().stream().filter(name -> name.equalsIgnoreCase("path"))
            .findFirst().orElse(isWindows() ? "Path" : "PATH");
        result.put(key, directory + File.pathSeparator + result.getOrDefault(key, ""));
        return result;
    }

    private static String which(String name, String extraPath) {
        List<Path> directories = new ArrayList<>();
        if (extraPath != null && !extraPath.isBlank()) directories.add(Path.of(extraPath));
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!directory.isBlank()) directories.add(Path.of(directory));
        }
        for (Path directory : directories) {
            for (String candidateName : isWindows() ? List.of(name, name + ".exe", name + ".cmd") : List.of(name)) {
                Path candidate = directory.resolve(candidateName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static boolean samePath(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) return false;
        return normalize(left).equals(normalize(right));
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private record Version(String value, String error) { }
}
