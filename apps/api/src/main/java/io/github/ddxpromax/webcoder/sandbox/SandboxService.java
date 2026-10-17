package io.github.ddxpromax.webcoder.sandbox;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Majo's Windows AppContainer command-execution route.  The configured mode
 * stays off by default; when AppContainer is requested but unavailable, a
 * command is never silently run outside the requested isolation boundary.
 */
@Service
public class SandboxService {
    public static final String MODE_OFF = "off";
    public static final String MODE_APPCONTAINER = "appcontainer";

    private static final Logger log = LoggerFactory.getLogger(
        SandboxService.class
    );
    private final AgentStore agents;

    public SandboxService(AgentStore agents) {
        this.agents = agents;
    }

    public boolean enabledFor(String agentId) {
        return MODE_APPCONTAINER.equals(modeOf(agentId))
            && AppContainerSandbox.isSupported();
    }

    public String modeOf(String agentId) {
        Object raw = settingsFor(agentId).get("mode");
        String mode = raw == null ? MODE_OFF : String.valueOf(raw)
            .trim().toLowerCase(Locale.ROOT);
        return mode.isBlank() ? MODE_OFF : mode;
    }

    public Map<String, Object> settingsFor(String agentId) {
        try {
            Object raw = agents.getRunningConfig(agentId).get("sandbox");
            if (raw instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> settings =
                    new java.util.LinkedHashMap<>((Map<String, Object>) map);
                return settings;
            }
        } catch (Exception exception) {
            log.debug("Sandbox configuration unavailable for {}: {}", agentId,
                exception.getMessage());
        }
        return Map.of();
    }

    public String run(
        String agentId,
        String command,
        Path workspace,
        int timeoutSeconds
    ) {
        try {
            String containerName = containerNameFor(workspace);
            String sid = AppContainerSandbox.ensureProfile(
                containerName,
                "webCoder Agent Container (" + agentId + ")",
                "webCoder execute_command isolation (Majo ADR-0012)"
            );
            AppContainerSandbox.grantPath(workspace.toString(), sid,
                AppContainerSandbox.Access.WORKSPACE_FULL);

            Map<String, Object> settings = settingsFor(agentId);
            List<Object> denied = values(settings.get("deny_paths"));
            for (Object grant : values(settings.get("extra_grant_paths"))) {
                Path path = Path.of(String.valueOf(grant));
                if (!Files.exists(path)) {
                    continue;
                }
                if (overlapsDeny(path, denied)) {
                    log.warn("Sandbox grant '{}' dropped because it overlaps deny_paths",
                        path);
                    continue;
                }
                AppContainerSandbox.grantPath(path.toString(), sid,
                    AppContainerSandbox.Access.READ_EXECUTE);
            }
            for (Object deny : denied) {
                Path path = Path.of(String.valueOf(deny));
                if (Files.exists(path)) {
                    AppContainerSandbox.grantPath(path.toString(), sid,
                        AppContainerSandbox.Access.DENY_ALL);
                }
            }
            List<String> capabilities = Boolean.TRUE.equals(
                settings.get("network_allow")
            ) ? List.of("internetClient", "internetClientServer",
                "privateNetworkClientServer") : List.of();
            AppContainerSandbox.Result result = AppContainerSandbox.run(
                "cmd.exe /c " + command, workspace.toString(), sid,
                AppContainerSandbox.capabilitySids(capabilities), timeoutSeconds
            );
            return (result.exitCode() == 0 ? "[success] "
                : "[warning] exit=" + result.exitCode() + " ")
                + result.output();
        } catch (Exception exception) {
            log.warn("AppContainer sandbox failed for agent '{}': {}", agentId,
                exception.getMessage());
            return "[error] Sandbox execution failed ("
                + exception.getMessage() + "); command was not run.";
        }
    }

    static String containerNameFor(Path workspace) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                workspace.toAbsolutePath().normalize().toString()
                    .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder value = new StringBuilder("webcoder_");
            for (int i = 0; i < 6; i++) {
                value.append(String.format("%02x", hash[i]));
            }
            return value.toString();
        } catch (Exception exception) {
            return "webcoder_default";
        }
    }

    static boolean overlapsDeny(Path candidate, List<Object> denyPaths) {
        String current = candidate.toAbsolutePath().normalize().toString()
            .replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Object denied : denyPaths) {
            String deniedPath = Path.of(String.valueOf(denied))
                .toAbsolutePath().normalize().toString().replace('\\', '/')
                .toLowerCase(Locale.ROOT);
            if (current.equals(deniedPath)
                || current.startsWith(deniedPath + "/")
                || deniedPath.startsWith(current + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> values(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }
}
