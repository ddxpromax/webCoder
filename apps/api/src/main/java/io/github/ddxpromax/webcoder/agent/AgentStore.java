package io.github.ddxpromax.webcoder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AgentStore {
    public static final String SCHEMA_VERSION = "agents.v1";
    public static final String DEFAULT_AGENT_ID = "default";
    public static final String DEFAULT_AGENT_NAME = "majo";
    public static final String DEFAULT_AGENT_DESC =
        "Majo AI Coding Agent";
    public static final int MAX_PROJECT_DIRS = 10;

    private static final Pattern AGENT_ID_PATTERN = Pattern.compile(
        "^[A-Za-z0-9_-]+$"
    );
    private static final Set<String> MUTABLE_TEXT_FIELDS = Set.of(
        "name", "description", "backend", "language"
    );
    private static final Set<String> MUTABLE_CONFIG_FIELDS = Set.of(
        "active_model", "running", "approval_level",
        "user_timezone", "acp", "tools", "coding_mode",
        "llm_routing"
    );

    private final MajoWorkingDirectory workingDirectory;
    private final JsonFileStore files;
    private final Path agentsFile;

    public AgentStore(
        MajoWorkingDirectory workingDirectory,
        JsonFileStore files
    ) {
        this.workingDirectory = workingDirectory;
        this.files = files;
        this.agentsFile = workingDirectory.resolve("agents.json");
    }

    public synchronized Map<String, Object> loadConfig() {
        if (!Files.isRegularFile(agentsFile)) {
            files.writeAtomic(agentsFile, defaultConfig());
        }
        Map<String, Object> config = files.read(
            agentsFile, defaultConfig()
        );
        Map<String, Object> profiles = profiles(config);
        if (!profiles.containsKey(DEFAULT_AGENT_ID)) {
            profiles.put(DEFAULT_AGENT_ID, defaultProfile());
            config.put("profiles", profiles);
            List<String> order = stringList(config.get("agent_order"));
            if (!order.contains(DEFAULT_AGENT_ID)) {
                order.add(0, DEFAULT_AGENT_ID);
                config.put("agent_order", order);
            }
            files.writeAtomic(agentsFile, config);
        }
        return config;
    }

    public synchronized List<Map<String, Object>> listProfiles() {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        List<String> orderedIds = stringList(
            config.get("agent_order")
        );
        for (String id : profiles.keySet()) {
            if (!orderedIds.contains(id)) {
                orderedIds.add(id);
            }
        }

        List<String> pinned = new ArrayList<>();
        List<String> regular = new ArrayList<>();
        for (String id : orderedIds) {
            if (!profiles.containsKey(id)
                || DEFAULT_AGENT_ID.equals(id)) {
                continue;
            }
            Map<String, Object> profile = asMap(profiles.get(id));
            if (Boolean.TRUE.equals(profile.get("pinned"))) {
                pinned.add(id);
            } else {
                regular.add(id);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (profiles.containsKey(DEFAULT_AGENT_ID)) {
            result.add(asMap(profiles.get(DEFAULT_AGENT_ID)));
        }
        for (String id : pinned) {
            result.add(asMap(profiles.get(id)));
        }
        for (String id : regular) {
            result.add(asMap(profiles.get(id)));
        }
        return result;
    }

    public synchronized Map<String, Object> getProfile(String agentId) {
        Map<String, Object> profile = asMap(
            profiles(loadConfig()).get(agentId)
        );
        return profile.isEmpty() ? null : profile;
    }

    public synchronized Map<String, Object> getRunningConfig(
        String agentId
    ) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        return asMap(profile.get("running"));
    }

    public synchronized String getApprovalLevel(String agentId) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        return text(profile.get("approval_level"), "AUTO");
    }

    public synchronized void saveRunningConfig(
        String agentId,
        Map<String, Object> running,
        String approvalLevel
    ) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("running", running);
        if (approvalLevel != null) {
            updates.put("approval_level", approvalLevel);
        }
        updateAgent(agentId, updates);
    }

    public Path workspaceDirForAgent(String agentId) {
        if (agentId == null || agentId.isBlank()
            || DEFAULT_AGENT_ID.equals(agentId)) {
            return defaultWorkspaceDir();
        }
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            throw new IllegalArgumentException(
                "Workspace '" + agentId + "' not found"
            );
        }
        String workspace = text(profile.get("workspace_dir"), "");
        if (workspace.isBlank()) {
            throw new IllegalArgumentException(
                "Workspace '" + agentId + "' not found"
            );
        }
        return Path.of(workspace).toAbsolutePath().normalize();
    }

    public Path defaultWorkspaceDir() {
        return workingDirectory.resolve("workspaces")
            .resolve(DEFAULT_AGENT_ID)
            .normalize();
    }

    public synchronized Map<String, Object> createAgent(
        Map<String, Object> spec
    ) {
        String id = text(spec.get("id"), "");
        validateNewAgentId(id);

        String rawWorkspace = text(spec.get("workspace_dir"), "");
        Path workspace = rawWorkspace.isBlank()
            ? workingDirectory.resolve("workspaces").resolve(id)
            : Path.of(rawWorkspace.trim());
        workspace = workspace.toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspace.resolve("skills"));
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot create workspace dir: " + workspace,
                exception
            );
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", id);
        profile.put("name", text(spec.get("name"), id));
        profile.put("description", text(spec.get("description"), ""));
        profile.put("workspace_dir", workspace.toString());
        profile.put("enabled", true);
        profile.put("pinned", false);
        profile.put("backend", text(spec.get("backend"), "majo"));
        profile.put("language", text(spec.get("language"), "en"));
        if (spec.get("active_model") != null) {
            profile.put("active_model", spec.get("active_model"));
        }

        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        profiles.put(id, profile);
        List<String> order = stringList(config.get("agent_order"));
        if (!order.contains(id)) {
            order.add(id);
        }
        config.put("profiles", profiles);
        config.put("agent_order", order);
        files.writeAtomic(agentsFile, config);
        return profile;
    }

    public synchronized Map<String, Object> updateAgent(
        String agentId,
        Map<String, Object> updates
    ) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        Map<String, Object> profile = asMap(profiles.get(agentId));
        if (profile.isEmpty()) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        for (String key : MUTABLE_TEXT_FIELDS) {
            if (updates.containsKey(key)) {
                profile.put(key, text(updates.get(key), ""));
            }
        }
        for (String key : List.of("enabled", "pinned")) {
            if (updates.containsKey(key)) {
                profile.put(
                    key,
                    Boolean.TRUE.equals(updates.get(key))
                );
            }
        }
        for (String key : MUTABLE_CONFIG_FIELDS) {
            if (updates.containsKey(key)) {
                Object value = updates.get(key);
                if (value == null) {
                    profile.remove(key);
                } else {
                    profile.put(key, value);
                }
            }
        }
        profiles.put(agentId, profile);
        config.put("profiles", profiles);
        files.writeAtomic(agentsFile, config);
        return profile;
    }

    public synchronized void deleteAgent(String agentId) {
        if (agentId == null || DEFAULT_AGENT_ID.equals(agentId)) {
            throw new IllegalArgumentException(
                "Cannot delete the default agent"
            );
        }
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        if (!profiles.containsKey(agentId)) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        profiles.remove(agentId);
        List<String> order = stringList(config.get("agent_order"));
        order.remove(agentId);
        config.put("profiles", profiles);
        config.put("agent_order", order);
        files.writeAtomic(agentsFile, config);
    }

    public synchronized void setAgentOrder(List<String> agentIds) {
        if (agentIds == null) {
            throw new IllegalArgumentException(
                "agent_ids must not be null"
            );
        }
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        String error =
            "Each configured agent ID must appear exactly once.";
        if (new HashSet<>(agentIds).size() != agentIds.size()) {
            throw new IllegalArgumentException(error);
        }
        for (String id : agentIds) {
            if (!profiles.containsKey(id)) {
                throw new IllegalArgumentException(error);
            }
        }
        config.put("agent_order", new ArrayList<>(agentIds));
        files.writeAtomic(agentsFile, config);
    }

    public synchronized String getProjectDir(String agentId) {
        Map<String, Object> profile = getProfile(agentId);
        return profile == null ? null
            : text(profile.get("project_dir"), null);
    }

    public synchronized List<Map<String, String>> getProjectDirs(
        String agentId
    ) {
        Map<String, Object> profile = getProfile(agentId);
        return profile == null ? List.of() : readProjectDirs(profile);
    }

    public synchronized void setProjectDir(
        String agentId,
        String projectDir
    ) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        Map<String, Object> profile = existingProfile(profiles, agentId);
        if (projectDir == null || projectDir.isBlank()) {
            profile.remove("project_dir");
        } else {
            profile.put("project_dir", projectDir);
            List<Map<String, String>> dirs = new ArrayList<>();
            dirs.add(Map.of("path", projectDir, "label", ""));
            for (Map<String, String> entry : readProjectDirs(profile)) {
                if (!projectDir.equals(entry.get("path"))
                    && dirs.size() < MAX_PROJECT_DIRS) {
                    dirs.add(entry);
                }
            }
            profile.put("project_dirs", dirs);
        }
        profiles.put(agentId, profile);
        config.put("profiles", profiles);
        files.writeAtomic(agentsFile, config);
    }

    public synchronized void setProjectDirs(
        String agentId,
        List<Map<String, String>> projectDirs
    ) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = profiles(config);
        Map<String, Object> profile = existingProfile(profiles, agentId);
        List<Map<String, String>> cleaned = new ArrayList<>();
        for (Map<String, String> entry : projectDirs == null
            ? List.<Map<String, String>>of() : projectDirs) {
            String path = entry == null ? null : entry.get("path");
            if (path == null || path.isBlank()) {
                continue;
            }
            if (!Files.isDirectory(
                Path.of(path.trim()).toAbsolutePath().normalize()
            )) {
                continue;
            }
            cleaned.add(Map.of(
                "path", path.trim(),
                "label", text(entry.get("label"), "")
            ));
            if (cleaned.size() >= MAX_PROJECT_DIRS) {
                break;
            }
        }
        if (cleaned.isEmpty()) {
            profile.remove("project_dirs");
            profile.remove("project_dir");
        } else {
            profile.put("project_dirs", cleaned);
            profile.put("project_dir", cleaned.getFirst().get("path"));
        }
        profiles.put(agentId, profile);
        config.put("profiles", profiles);
        files.writeAtomic(agentsFile, config);
    }

    private static List<Map<String, String>> readProjectDirs(
        Map<String, Object> profile
    ) {
        List<Map<String, String>> result = new ArrayList<>();
        if (profile.get("project_dirs") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry
                    && entry.get("path") != null) {
                    result.add(Map.of(
                        "path", String.valueOf(entry.get("path")),
                        "label", text(entry.get("label"), "")
                    ));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> existingProfile(
        Map<String, Object> profiles,
        String agentId
    ) {
        Map<String, Object> profile = asMap(profiles.get(agentId));
        if (profile.isEmpty()) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "' not found"
            );
        }
        return profile;
    }

    public void setAgentEnabled(String agentId, boolean enabled) {
        if (DEFAULT_AGENT_ID.equals(agentId) && !enabled) {
            throw new IllegalArgumentException(
                "Cannot disable the default agent"
            );
        }
        updateAgent(agentId, Map.of("enabled", enabled));
    }

    public void setAgentPinned(String agentId, boolean pinned) {
        if (DEFAULT_AGENT_ID.equals(agentId) && !pinned) {
            throw new IllegalArgumentException(
                "Cannot unpin the default agent"
            );
        }
        updateAgent(agentId, Map.of("pinned", pinned));
    }

    private void validateNewAgentId(String id) {
        if (id.isBlank()) {
            throw new IllegalArgumentException(
                "Agent ID must not be empty"
            );
        }
        if (id.length() < 2 || id.length() > 64) {
            throw new IllegalArgumentException(
                "Agent ID must be between 2 and 64 characters, got "
                    + id.length()
            );
        }
        if (!AGENT_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                "Agent ID '" + id + "' contains invalid characters. "
                    + "Use letters, digits, '-' or '_'."
            );
        }
        if (DEFAULT_AGENT_ID.equals(id)) {
            throw new IllegalArgumentException(
                "Agent ID '" + id
                    + "' is reserved and cannot be used."
            );
        }
        if (getProfile(id) != null) {
            throw new IllegalArgumentException(
                "Agent ID '" + id + "' already exists."
            );
        }
    }

    private Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", SCHEMA_VERSION);
        config.put("active_agent", DEFAULT_AGENT_ID);
        config.put("agent_order", new ArrayList<>(
            List.of(DEFAULT_AGENT_ID)
        ));
        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_AGENT_ID, defaultProfile());
        config.put("profiles", profiles);
        return config;
    }

    private Map<String, Object> defaultProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", DEFAULT_AGENT_ID);
        profile.put("name", DEFAULT_AGENT_NAME);
        profile.put("description", DEFAULT_AGENT_DESC);
        profile.put("workspace_dir", defaultWorkspaceDir().toString());
        profile.put("enabled", true);
        profile.put("pinned", true);
        profile.put("backend", "majo");
        profile.put("language", "en");
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    private static Map<String, Object> profiles(
        Map<String, Object> config
    ) {
        return asMap(config.get("profiles"));
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private static String text(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
