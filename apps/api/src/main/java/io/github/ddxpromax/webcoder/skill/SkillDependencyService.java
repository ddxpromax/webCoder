package io.github.ddxpromax.webcoder.skill;

import io.github.ddxpromax.webcoder.mcp.McpService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Majo-compatible runtime validation for SKILL.md `requires` declarations. */
@Service
public class SkillDependencyService {
    private final McpService mcp;
    public SkillDependencyService(McpService mcp) { this.mcp = mcp; }
    public Map<String, Object> check(Path workspace, String name, String content) {
        Map<String, Object> frontmatter = SkillWorkspaceService.parseFrontmatter(content);
        Map<String, Object> requires = map(frontmatter.get("requires"));
        List<String> bins = strings(requires.get("bins")), env = strings(requires.get("env")), servers = strings(requires.get("mcp"));
        List<String> missing = new ArrayList<>();
        for (String variable : env) if (System.getenv(variable) == null || System.getenv(variable).isBlank()) missing.add("Environment variable not set: " + variable);
        for (String binary : bins) if (which(binary) == null) missing.add("CLI binary not found on PATH: " + binary);
        for (String server : servers) { try { if (!mcp.get(server).enabled()) missing.add("MCP server is disabled: " + server); } catch (IllegalArgumentException exception) { missing.add("MCP server not configured: " + server); } }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("name", name); result.put("requirements", Map.of("bins", bins, "env", env, "mcp", servers)); result.put("declaration_errors", List.of()); result.put("missing_dependencies", missing); result.put("dependencies_met", missing.isEmpty()); return result;
    }
    private static String which(String binary) { if (binary == null || binary.isBlank()) return null; for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) { Path path = Path.of(dir).resolve(binary); if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toString(); } return null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of(); }
    private static List<String> strings(Object value) { if (!(value instanceof List<?> values)) return List.of(); return values.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList(); }
}
