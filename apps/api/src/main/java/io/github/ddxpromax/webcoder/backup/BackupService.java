package io.github.ddxpromax.webcoder.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/** Majo archive store: scoped ZIP creation, signing, trust checks and restores. */
@Service
public class BackupService {
    private static final String WORKSPACES = "data/workspaces/";
    private static final String CONFIG = "data/config.json";
    private static final String SECRETS = "data/secrets/";
    private static final String SKILL_POOL = "data/skill_pool/";
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final MajoWorkingDirectory working;
    private final AgentStore agents;
    private final ObjectMapper mapper;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "majo-backup");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile String activeId;

    public BackupService(MajoWorkingDirectory working, AgentStore agents, ObjectMapper mapper) {
        this.working = working;
        this.agents = agents;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> backups = new ArrayList<>();
        try (var paths = Files.list(directory())) {
            for (Path archive : paths.filter(this::isArchive).toList()) {
                Map<String, Object> metadata = meta(archive);
                if (metadata != null) backups.add(publicMeta(metadata));
            }
        }
        backups.sort((left, right) -> String.valueOf(right.get("created_at"))
            .compareTo(String.valueOf(left.get("created_at"))));
        return backups;
    }

    public Map<String, Object> detail(String id) throws IOException {
        Path archive = find(id);
        if (archive == null) return null;
        Map<String, Object> metadata = meta(archive);
        if (metadata == null) return null;
        Map<String, Object> detail = publicMeta(metadata);
        detail.put("workspace_stats", workspaceStats(archive));
        return detail;
    }

    public Path export(String id) throws IOException {
        Path archive = find(id);
        if (archive == null) throw new IllegalArgumentException("Backup not found");
        return archive;
    }

    public synchronized Map<String, Object> start(Map<String, Object> body) {
        if (activeId != null && jobs.containsKey(activeId) && !jobs.get(activeId).terminal()) {
            throw new IllegalStateException("A backup job is already running");
        }
        Job job = new Job();
        jobs.put(job.id, job);
        activeId = job.id;
        worker.submit(() -> create(job, body));
        return job.view();
    }

    public Map<String, Object> job(String id) {
        Job job = jobs.get(id);
        return job == null ? null : job.view();
    }

    public Map<String, Object> active() { return activeId == null ? Map.of() : job(activeId); }

    public Map<String, Object> cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) return null;
        job.cancel = true;
        if (!job.terminal()) job.status = "cancel_requested";
        return job.view();
    }

    public Map<String, Object> delete(List<String> ids) throws IOException {
        List<String> deleted = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (String id : ids) {
            Path archive = find(id);
            if (archive == null) {
                failed.add(Map.of("id", id, "reason", "not found"));
                continue;
            }
            try {
                Files.delete(archive);
                deleted.add(id);
            } catch (IOException exception) {
                failed.add(Map.of("id", id, "reason", exception.getMessage()));
            }
        }
        return Map.of("deleted", deleted, "failed", failed);
    }

    public Path createUploadTemp(String trustMode) throws IOException {
        String suffix = "legacy".equals(trustMode) ? ".upload_tmp.trust_legacy"
            : "foreign".equals(trustMode) ? ".upload_tmp.trust_foreign" : ".upload_tmp";
        return Files.createTempFile(directory(), "upload-", suffix);
    }

    public Map<String, Object> importZip(Path upload, boolean overwrite, String trustMode) throws IOException {
        if (!BackupArchiveTrust.isZip(upload)) {
            throw new IllegalArgumentException("Uploaded file is not a valid zip archive");
        }
        Map<String, Object> metadata = meta(upload);
        if (metadata == null || !validId(text(metadata.get("id")))) {
            throw new IllegalArgumentException("Zip does not contain a valid meta.json");
        }
        validateTrust(upload, metadata, trustMode);
        String id = text(metadata.get("id"));
        Path existing = find(id);
        if (existing != null && !overwrite) throw new Conflict(id);
        if (existing != null && !existing.equals(archivePath(id))) Files.deleteIfExists(existing);
        Files.move(upload, archivePath(id), StandardCopyOption.REPLACE_EXISTING);
        return publicMeta(metadata);
    }

    public Map<String, Object> resolvePending(String token) throws IOException {
        Path candidate = directory().resolve(token).normalize();
        if (!candidate.startsWith(directory().toAbsolutePath().normalize()) || !Files.isRegularFile(candidate)
            || !candidate.getFileName().toString().startsWith("upload-")) {
            throw new IllegalArgumentException("Invalid pending_token");
        }
        String name = candidate.getFileName().toString();
        String trust = name.contains("trust_legacy") ? "legacy"
            : name.contains("trust_foreign") ? "foreign" : null;
        try {
            return importZip(candidate, true, trust);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    public Map<String, Object> restore(String id, Map<String, Object> request) throws IOException {
        Path archive = find(id);
        if (archive == null) throw new IllegalArgumentException("Backup not found");
        Map<String, Object> metadata = meta(archive);
        if (metadata == null) throw new IllegalArgumentException("Backup not found");
        validateTrust(archive, metadata, textOrNull(request.get("trust_mode")));
        if (!"1".equals(text(metadata.getOrDefault("version", "1")))) {
            throw new IllegalArgumentException("Unsupported backup version: " + metadata.get("version"));
        }
        boolean includeAgents = bool(request.get("include_agents"), true);
        boolean includeConfig = bool(request.get("include_global_config"), true);
        boolean includeSecrets = bool(request.get("include_secrets"), false);
        boolean includeSkillPool = bool(request.get("include_skill_pool"), true);
        List<String> selected = strings(request.get("agent_ids"));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (selected.isEmpty()) selected = archiveAgentIds(zip);
            if (includeAgents) restoreWorkspaces(zip, selected, textOrNull(request.get("default_workspace_dir")));
            if (includeSkillPool) extractPrefix(zip, SKILL_POOL, working.resolve("skill_pool"));
            if (includeSecrets) {
                Path secrets = working.resolve(".secret");
                extractPrefix(zip, SECRETS, secrets);
                hardenSecrets(secrets);
            }
            List<String> preserved = includeConfig ? restoreConfig(zip, selected, includeAgents, request) : List.of();
            return Map.of("ok", true, "preserved_local_keys", preserved);
        }
    }

    private void create(Job job, Map<String, Object> body) {
        Path temporary = null;
        try {
            job.phase = "preparing";
            Map<String, Object> scope = asMap(body.get("scope"));
            boolean includeAgents = bool(scope.get("include_agents"), true);
            List<String> requested = strings(body.get("agents"));
            List<Map<String, Object>> profiles = includeAgents ? agents.listProfiles() : List.of();
            if (!requested.isEmpty()) profiles = profiles.stream().filter(profile -> requested.contains(text(profile.get("id")))).toList();
            job.total = profiles.size();
            Map<String, Object> metadata = metadata(job, body, scope, profiles.size());
            Path target = archivePath(job.backupId);
            temporary = target.resolveSibling(target.getFileName() + ".unsigned.tmp");
            Files.deleteIfExists(temporary);
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Map<String, Object> profile : profiles) {
                    if (job.cancel) throw new Cancelled();
                    String agentId = text(profile.get("id"));
                    job.current = agentId;
                    zipDirectory(output, agents.workspaceDirForAgent(agentId), WORKSPACES + agentId + "/");
                    job.index++;
                    job.percent = 10 + (int) (75.0 * job.index / Math.max(1, job.total));
                }
                if (bool(scope.get("include_global_config"), true)) {
                    Path config = working.resolve("agents.json");
                    if (Files.isRegularFile(config)) add(output, config, CONFIG);
                }
                if (bool(scope.get("include_secrets"), false)) zipDirectory(output, working.resolve(".secret"), SECRETS);
                if (bool(scope.get("include_skill_pool"), true)) zipDirectory(output, working.resolve("skill_pool"), SKILL_POOL);
                output.putNextEntry(new ZipEntry("meta.json"));
                output.write(mapper.writeValueAsBytes(metadata));
                output.closeEntry();
            }
            job.phase = "finalizing";
            job.percent = 90;
            BackupArchiveTrust.signAndFinalize(temporary, target, metadata, mapper, signingKeyPath());
            temporary = null;
            job.result = publicMeta(metadata);
            job.status = "completed";
            job.phase = "completed";
            job.percent = 100;
        } catch (Cancelled cancelled) {
            job.status = "cancelled";
            job.error = "Backup cancelled";
        } catch (Exception exception) {
            job.status = "failed";
            job.error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            if (job.id.equals(activeId)) activeId = null;
        }
    }

    private Map<String, Object> metadata(Job job, Map<String, Object> body, Map<String, Object> scope, int agentCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", job.backupId);
        metadata.put("name", text(body.get("name")));
        metadata.put("description", text(body.get("description")));
        metadata.put("created_at", Instant.now().toString());
        metadata.put("version", "1");
        metadata.put("scope", Map.of("include_agents", bool(scope.get("include_agents"), true),
            "include_global_config", bool(scope.get("include_global_config"), true),
            "include_secrets", bool(scope.get("include_secrets"), false),
            "include_skill_pool", bool(scope.get("include_skill_pool"), true)));
        metadata.put("agent_count", agentCount);
        metadata.put("majo_version", "majo");
        metadata.put("system_info", Map.of("os", System.getProperty("os.name"),
            "os_version", System.getProperty("os.version"), "machine", System.getProperty("os.arch"),
            "java_version", System.getProperty("java.version")));
        return metadata;
    }

    private List<String> restoreConfig(ZipFile zip, List<String> selected, boolean includeAgents, Map<String, Object> request) throws IOException {
        ZipEntry entry = zip.getEntry(CONFIG);
        if (entry == null) return List.of();
        Map<String, Object> backup = mapper.readValue(zip.getInputStream(entry), new TypeReference<LinkedHashMap<String, Object>>() { });
        Map<String, Object> current = agents.loadConfig();
        Map<String, Object> merged = new LinkedHashMap<>(backup);
        boolean preserve = request.containsKey("preserve_local_protected_config")
            ? Boolean.TRUE.equals(request.get("preserve_local_protected_config")) : !trustedRestore(textOrNull(request.get("trust_mode")));
        List<String> preserved = new ArrayList<>();
        if (preserve) for (String key : List.of("active_model", "providers", "settings", "llm_routing")) {
            if (current.containsKey(key)) { merged.put(key, current.get(key)); preserved.add(key); }
        }
        if ("custom".equals(text(request.getOrDefault("mode", "custom")))) {
            Map<String, Object> profiles = new LinkedHashMap<>(asMap(current.get("profiles")));
            Map<String, Object> savedProfiles = asMap(backup.get("profiles"));
            if (includeAgents) for (String agentId : selected) {
                if (savedProfiles.containsKey(agentId)) profiles.put(agentId, savedProfiles.get(agentId));
            }
            merged.put("profiles", profiles);
        }
        agents.replaceConfig(merged);
        return preserved;
    }

    private void restoreWorkspaces(ZipFile zip, List<String> ids, String baseDirectory) throws IOException {
        for (String id : ids) {
            Map<String, Object> profile = agents.getProfile(id);
            Path destination = profile != null && !text(profile.get("workspace_dir")).isBlank()
                ? Path.of(text(profile.get("workspace_dir")))
                : baseDirectory != null && !baseDirectory.isBlank() ? Path.of(baseDirectory).resolve(id)
                : working.resolve("workspaces").resolve(id);
            extractPrefix(zip, WORKSPACES + id + "/", destination);
        }
    }

    private void extractPrefix(ZipFile zip, String prefix, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix)) continue;
            String relative = entry.getName().substring(prefix.length());
            if (relative.isBlank()) continue;
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IOException("Unsafe zip path: " + entry.getName());
            if (entry.isDirectory()) Files.createDirectories(target);
            else {
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private void hardenSecrets(Path directory) {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) Files.setPosixFilePermissions(path, Files.isDirectory(path)
                ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) { }
    }

    private void zipDirectory(ZipOutputStream output, Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                add(output, file, prefix + root.relativize(file).toString().replace('\\', '/'));
            }
        }
    }

    private void add(ZipOutputStream output, Path file, String name) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private Map<String, Object> workspaceStats(Path archive) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(WORKSPACES)) continue;
                String[] parts = entry.getName().split("/", 4);
                if (parts.length < 4) continue;
                @SuppressWarnings("unchecked") Map<String, Object> stat = (Map<String, Object>) stats.computeIfAbsent(parts[2],
                    ignored -> new LinkedHashMap<>(Map.of("files", 0, "size", 0L)));
                stat.put("files", ((Number) stat.get("files")).intValue() + 1);
                stat.put("size", ((Number) stat.get("size")).longValue() + Math.max(0, entry.getSize()));
            }
        }
        return stats;
    }

    private List<String> archiveAgentIds(ZipFile zip) {
        List<String> ids = new ArrayList<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String[] parts = entry.getName().split("/", 4);
            if (!entry.isDirectory() && entry.getName().startsWith(WORKSPACES) && parts.length >= 4 && !ids.contains(parts[2])) ids.add(parts[2]);
        }
        return ids;
    }

    private void validateTrust(Path archive, Map<String, Object> metadata, String trustMode) {
        Object signature = metadata.get("signature");
        if (signature instanceof String value && !value.isBlank()) {
            if (BackupArchiveTrust.verifies(archive, metadata, mapper, signingKeyPath()) || "foreign".equals(trustMode)) return;
        } else if ("legacy".equals(trustMode)) return;
        throw new IllegalArgumentException("Backup signature is invalid or untrusted");
    }

    private boolean trustedRestore(String trustMode) { return "legacy".equals(trustMode) || "foreign".equals(trustMode); }
    private Path directory() throws IOException { Path directory = working.resolve(".backups"); Files.createDirectories(directory); return directory; }
    private Path signingKeyPath() { return working.resolve(".backups").resolve("signing.key"); }
    private Path archivePath(String id) throws IOException { return directory().resolve(id + ".zip"); }
    private Path find(String id) throws IOException {
        if (!validId(id)) return null;
        Path direct = archivePath(id);
        if (Files.isRegularFile(direct)) return direct;
        try (var paths = Files.list(directory())) {
            return paths.filter(this::isArchive).filter(path -> {
                try { Map<String, Object> metadata = meta(path); return metadata != null && id.equals(text(metadata.get("id"))); }
                catch (IOException ignored) { return false; }
            }).findFirst().orElse(null);
        }
    }
    private boolean isArchive(Path path) { return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".zip"); }
    private Map<String, Object> meta(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("meta.json");
            return entry == null ? null : mapper.readValue(zip.getInputStream(entry), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException exception) { return null; }
    }
    private Map<String, Object> publicMeta(Map<String, Object> metadata) { Map<String, Object> result = new LinkedHashMap<>(metadata); result.remove("signature"); return result; }
    @SuppressWarnings("unchecked") private Map<String, Object> asMap(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of(); }
    private List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String textOrNull(Object value) { String text = text(value); return text.isBlank() ? null : text; }
    private boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.TRUE.equals(value); }
    private boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9._-]{1,200}"); }

    private static final class Cancelled extends RuntimeException { }
    private static final class Job {
        private final String id = "bkjob-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        private final String backupId = "majo-majo-" + ID_TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        private volatile String status = "running", phase = "preparing", current = "", error = "";
        private volatile int percent, index, total;
        private volatile boolean cancel;
        private volatile Map<String, Object> result;
        private boolean terminal() { return status.equals("completed") || status.equals("failed") || status.equals("cancelled"); }
        private Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("job_id", id); view.put("backup_id", backupId); view.put("status", status); view.put("phase", phase);
            view.put("percent", percent); view.put("current_agent", current); view.put("agent_index", index); view.put("total_agents", total);
            view.put("result", result); view.put("error", error); return view;
        }
    }
    public static final class Conflict extends IllegalArgumentException { public Conflict(String id) { super(id); } }
}
