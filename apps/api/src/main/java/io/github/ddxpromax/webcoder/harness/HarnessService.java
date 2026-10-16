package io.github.ddxpromax.webcoder.harness;

import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Majo's third-party coding-agent harness catalog and local state.
 *
 * <p>The catalog advertises UI capabilities and detects a locally installed
 * CLI. It deliberately does not start a Codex or Qoder app-server session:
 * Majo uses this API as the availability and configuration boundary for that
 * future runtime integration.
 */
@Service
public class HarnessService {
    private final MajoWorkingDirectory working;
    private final JsonFileStore files;

    public HarnessService(MajoWorkingDirectory working, JsonFileStore files) {
        this.working = working;
        this.files = files;
    }

    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CatalogItem item : catalog()) {
            result.add(providerStatus(item.id(), Map.of()));
        }
        return result;
    }

    public Map<String, Object> capabilities(String providerId) {
        return item(providerId).capabilities();
    }

    public Map<String, Object> providerStatus(
        String providerId, Map<String, Object> settings
    ) {
        CatalogItem provider = item(providerId);
        String configured = text(settings.get("binary"));
        String runtimePath = resolveBinary(configured.isBlank()
            ? binaryName(provider.id()) : configured);
        boolean authenticated = isAuthenticated(provider.id());

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("id", provider.id());
        status.put("name", provider.name());
        status.put("available", !provider.comingSoon());
        status.put("coming_soon", provider.comingSoon());
        status.put("installed", runtimePath != null);
        status.put("authenticated", authenticated);
        status.put("account", authenticated ? localAccount() : null);
        status.put("runtime_path", runtimePath);
        status.put("runtime_source", runtimePath == null ? null : "path");
        status.put("error", null);
        status.put("capabilities", provider.capabilities());
        return status;
    }

    public Map<String, Object> login(
        String providerId, boolean deviceCode, Map<String, Object> settings
    ) {
        CatalogItem provider = item(providerId);
        if (provider.comingSoon()) {
            throw new IllegalArgumentException(provider.name()
                + " is not available yet");
        }
        String configured = text(settings.get("binary"));
        if (resolveBinary(configured.isBlank()
            ? binaryName(provider.id()) : configured) == null) {
            throw new IllegalArgumentException(provider.name()
                + " CLI is not installed");
        }
        Map<String, Object> auth = loadAuth();
        auth.put(provider.id(), "local-" + UUID.randomUUID()
            .toString().substring(0, 8));
        files.writeAtomic(authFile(), auth);
        return Map.of(
            "type", deviceCode ? "device_code" : "browser_redirect",
            "loginId", UUID.randomUUID().toString()
        );
    }

    public boolean logout(String providerId) {
        item(providerId);
        Map<String, Object> auth = loadAuth();
        if (auth.remove(providerId) == null) {
            return false;
        }
        files.writeAtomic(authFile(), auth);
        return true;
    }

    private CatalogItem item(String providerId) {
        return catalog().stream()
            .filter(entry -> entry.id().equals(providerId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown third-party agent backend: " + providerId));
    }

    private List<CatalogItem> catalog() {
        return List.of(
            new CatalogItem("codex", "Codex", false, codexCapabilities()),
            new CatalogItem("claude", "Claude Code", true,
                unavailableCapabilities()),
            new CatalogItem("qoder", "Qoder", false, qoderCapabilities())
        );
    }

    private Map<String, Object> codexCapabilities() {
        Map<String, Object> result = commonCapabilities(true);
        result.put("provider_mcp_discovery", true);
        result.put("commands", List.of(
            command("compact", "Compact the current Codex thread", false),
            command("review", "Review uncommitted workspace changes", false),
            command("skills", "List skills available to Codex", false),
            command("status", "Show Codex account and session status", false)
        ));
        result.put("approval_presets", List.of(
            preset("ask", "Ask before changes",
                "Allow workspace changes and ask before elevated actions.",
                Map.of("sandbox", "workspace-write", "approval_policy", "on-request")),
            preset("read-only", "Read only", "Inspect files without changing them.",
                Map.of("sandbox", "read-only", "approval_policy", "on-request")),
            preset("workspace", "Workspace access",
                "Allow workspace changes without confirmation.",
                Map.of("sandbox", "workspace-write", "approval_policy", "never")),
            preset("full-access", "Full access",
                "Allow unrestricted local execution without confirmation.",
                Map.of("sandbox", "danger-full-access", "approval_policy", "never"))
        ));
        return result;
    }

    private Map<String, Object> qoderCapabilities() {
        Map<String, Object> result = commonCapabilities(true);
        result.put("provider_mcp_discovery", false);
        result.put("commands", List.of(
            command("compact", "Compact the current Qoder session", false)
        ));
        result.put("approval_presets", List.of(
            preset("ask", "Ask before actions",
                "Ask before file changes and command execution.",
                Map.of("permission_mode", "default")),
            preset("accept-edits", "Accept edits",
                "Allow file edits while keeping other safeguards.",
                Map.of("permission_mode", "acceptEdits")),
            preset("plan", "Plan only",
                "Analyze and plan without changing files.",
                Map.of("permission_mode", "plan")),
            preset("auto", "Automatic",
                "Let Qoder decide which safe actions can run.",
                Map.of("permission_mode", "auto")),
            preset("full-access", "Full access",
                "Skip permission checks in a trusted workspace.",
                Map.of("permission_mode", "bypassPermissions"))
        ));
        return result;
    }

    private Map<String, Object> commonCapabilities(boolean supported) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authentication", supported);
        result.put("model_selection", supported);
        result.put("reasoning_effort", supported);
        result.put("reasoning_stream", supported);
        result.put("tool_stream", supported);
        result.put("session_resume", supported);
        result.put("workspace_ui", false);
        result.put("native_skills_ui", false);
        result.put("native_tools_ui", false);
        result.put("native_mcp_ui", false);
        result.put("loop_modes", false);
        result.put("attachments", supported);
        result.put("context_usage", false);
        result.put("skills_commands", false);
        result.put("majo_skills_projection", supported);
        result.put("majo_mcp_projection", supported);
        result.put("provider_skills_discovery", supported);
        result.put("provider_mcp_discovery", false);
        result.put("mcp_tool_allowlist", supported);
        return result;
    }

    private Map<String, Object> unavailableCapabilities() {
        Map<String, Object> result = commonCapabilities(false);
        result.put("commands", List.of());
        result.put("approval_presets", List.of());
        return result;
    }

    private Map<String, Object> localAccount() {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("type", "local");
        account.put("username", null);
        account.put("email", null);
        account.put("planType", null);
        return account;
    }

    private Map<String, Object> command(
        String name, String description, boolean acceptsArguments
    ) {
        return Map.of("name", name, "description", description,
            "accepts_arguments", acceptsArguments);
    }

    private Map<String, Object> preset(
        String id, String name, String description, Map<String, Object> settings
    ) {
        return Map.of("id", id, "name", name, "description", description,
            "settings", settings);
    }

    private String binaryName(String providerId) {
        return "codex".equals(providerId) ? "codex"
            : "qoder".equals(providerId) ? "qoder" : "";
    }

    private String resolveBinary(String binary) {
        if (binary == null || binary.isBlank()) {
            return null;
        }
        Path direct = Path.of(binary);
        if (Files.isExecutable(direct)) {
            return direct.toString();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(
            java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, binary);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private boolean isAuthenticated(String providerId) {
        Object marker = loadAuth().get(providerId);
        return marker instanceof String value && !value.isBlank();
    }

    private Map<String, Object> loadAuth() {
        return files.read(authFile(), Map.of());
    }

    private Path authFile() {
        return working.resolve("harnesses").resolve("auth.json");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record CatalogItem(
        String id, String name, boolean comingSoon, Map<String, Object> capabilities
    ) { }
}
