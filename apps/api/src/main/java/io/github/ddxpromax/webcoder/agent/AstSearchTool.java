package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Majo-compatible structural search backed by the local ast-grep CLI. */
public class AstSearchTool {
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT = 80_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path workspace;
    private final String agentId;
    private final CodingModeService codingMode;

    public AstSearchTool(Path workspace, String agentId, CodingModeService codingMode) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.agentId = agentId;
        this.codingMode = codingMode;
    }

    @Tool(name = "ast_search", description = "Search source code using ast-grep patterns", readOnly = true, concurrencySafe = true)
    public String astSearch(
        @ToolParam(name = "pattern", description = "ast-grep pattern; optional when kind is present", required = false) String pattern,
        @ToolParam(name = "language", description = "Language, for example java, python, or typescript") String language,
        @ToolParam(name = "path", description = "Optional workspace-relative file or directory", required = false) String path,
        @ToolParam(name = "max_matches", description = "Maximum matches, default 200, maximum 1000", required = false) Integer maxMatches,
        @ToolParam(name = "kind", description = "Optional AST node kind", required = false) String kind
    ) {
        if (!codingMode.isEnabled(agentId)) return CodingModeService.disabledReply();
        boolean kindMode = kind != null && !kind.isBlank();
        if (!kindMode && (pattern == null || pattern.isBlank())) return "pattern is required unless kind is provided.";
        if (language == null || language.isBlank()) return "language is required.";
        if (kindMode && !kind.strip().matches("[A-Za-z0-9_]+")) return "kind may contain only letters, numbers, and underscores.";
        String binary = detectBinary();
        if (binary == null) return "ast-grep is not installed. Install ast-grep-cli to use ast_search (binary: ast-grep or sg).";
        Path scope;
        try { scope = resolve(path); } catch (IllegalArgumentException exception) { return exception.getMessage(); }
        int limit = maxMatches == null ? 200 : Math.max(1, Math.min(maxMatches, 1000));
        List<String> command = new ArrayList<>(); command.add(binary);
        if (kindMode) { command.add("scan"); command.add("--inline-rules"); command.add(rule(kind.strip(), pattern, language.strip())); }
        else { command.add("run"); command.add("--pattern"); command.add(pattern); command.add("--lang"); command.add(language.strip()); }
        command.add("--json=compact"); command.add(scope.toString());
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { process.destroyForcibly(); return "ast-grep timed out after 30 seconds. Narrow the path or pattern."; }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && process.exitValue() != 1) return "ast-grep failed: " + truncate(output);
            return render(output, limit);
        } catch (IOException exception) { return "ast-grep failed: " + exception.getMessage(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); return "ast-grep was interrupted."; }
        finally { if (process != null) process.destroyForcibly(); }
    }

    static String detectBinary() {
        String path = System.getenv("PATH"); if (path == null) return null;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String directory : path.split(java.io.File.pathSeparator)) for (String name : windows ? List.of("ast-grep.exe", "sg.exe", "ast-grep", "sg") : List.of("ast-grep", "sg")) {
            Path candidate = Path.of(directory, name); if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    static String rule(String kind, String pattern, String language) {
        String regex = pattern == null || pattern.isBlank() ? "" : "  regex: '" + pattern.strip().replace("'", "''") + "'\n";
        return "rule:\n  kind: " + kind + "\n" + regex + "language: " + language;
    }

    String render(String raw, int limit) throws IOException {
        if (raw == null || raw.isBlank()) return "No matches for pattern in the given language.";
        JsonNode matches = JSON.readTree(raw); if (!matches.isArray() || matches.isEmpty()) return "No matches for pattern in the given language.";
        StringBuilder result = new StringBuilder("{\"matches\":["); int count = 0;
        for (JsonNode match : matches) { if (count >= limit || result.length() > MAX_OUTPUT) break; if (count++ > 0) result.append(','); String text = match.path("text").asText(""); if (text.length() > 400) text = text.substring(0, 400); result.append("{\"file\":\"").append(escape(match.path("file").asText("").replace('\\','/'))).append("\",\"line\":").append(match.path("range").path("start").path("line").asInt()+1).append(",\"column\":").append(match.path("range").path("start").path("column").asInt()+1).append(",\"snippet\":\"").append(escape(text)).append("\"}"); }
        return result.append("],\"truncated\":").append(count < matches.size() || result.length() > MAX_OUTPUT).append('}').toString();
    }

    private Path resolve(String input) { if (input == null || input.isBlank()) return workspace; Path target = workspace.resolve(input).normalize(); if (!target.startsWith(workspace)) throw new IllegalArgumentException("Path is outside the workspace: " + input); return target; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
    private static String truncate(String value) { return value.length() <= 2000 ? value : value.substring(0, 2000) + "..."; }
}
