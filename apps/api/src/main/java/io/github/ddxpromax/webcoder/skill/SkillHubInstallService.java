package io.github.ddxpromax.webcoder.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Background installer for the direct JSON and ClawHub bundle formats used by
 * Majo. Workspace creation remains in {@link SkillWorkspaceService}, so its
 * path validation and security scan also protect Hub imports.
 */
@Service
public class SkillHubInstallService {
    private static final long MAX_BUNDLE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_PACKAGE_ENTRIES = 4_096;
    private static final String CLAWHUB = "https://clawhub.ai";

    private final SkillWorkspaceService skills;
    private final SkillHubTaskService tasks;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ExecutorService workers = Executors.newCachedThreadPool(
        runnable -> {
            Thread thread = new Thread(runnable, "majo-skill-hub");
            thread.setDaemon(true);
            return thread;
        }
    );

    public SkillHubInstallService(
        SkillWorkspaceService skills,
        SkillHubTaskService tasks
    ) {
        this.skills = skills;
        this.tasks = tasks;
    }

    public SkillHubTaskService.Task start(
        Path workspace,
        String bundleUrl,
        String version,
        boolean enable,
        String targetName
    ) {
        validateUrl(bundleUrl);
        SkillHubTaskService.Task task = tasks.create(
            bundleUrl, version, enable
        );
        workers.execute(() -> install(
            task.taskId(), workspace, targetName == null ? "" : targetName
        ));
        return task;
    }

    public SkillHubTaskService.Task status(String taskId) {
        return tasks.get(taskId);
    }

    public String cancel(String taskId) {
        return tasks.cancel(taskId);
    }

    /** Synchronous Majo Pool import; Pool skills are never enabled directly. */
    public Map<String, Object> importToPool(
        SkillPoolService pool,
        String bundleUrl,
        String version,
        String targetName
    ) {
        validateUrl(bundleUrl);
        try {
            Bundle bundle = resolve(bundleUrl, version == null ? "" : version,
                new AtomicBoolean(false));
            Map<String, Object> created = pool.create(
                targetName == null || targetName.isBlank() ? bundle.name() : targetName,
                bundle.content(), bundle.references(), bundle.scripts(), null,
                bundle.installedFrom()
            );
            if (!Boolean.TRUE.equals(created.get("success"))) {
                throw new IllegalStateException("A skill with this name already exists");
            }
            return Map.of(
                "installed", true,
                "name", created.get("name"),
                "enabled", false,
                "source_url", bundle.sourceUrl(),
                "installed_from", bundle.installedFrom()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not import skill from Hub", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not import skill from Hub", exception);
        }
    }

    private void install(String taskId, Path workspace, String targetName) {
        String createdName = null;
        AtomicBoolean cancelled = tasks.cancellation(taskId);
        try {
            tasks.importing(taskId);
            Bundle bundle = resolve(
                tasks.get(taskId).bundleUrl(), tasks.get(taskId).version(),
                cancelled
            );
            ensureNotCancelled(cancelled);
            String name = targetName.isBlank() ? bundle.name() : targetName;
            Map<String, Object> created = skills.create(
                workspace, name, bundle.content(), bundle.references(),
                bundle.scripts(), bundle.extraFiles(), null,
                tasks.get(taskId).enable()
            );
            if (!Boolean.TRUE.equals(created.get("success"))) {
                throw new IllegalStateException("A skill with this name already exists");
            }
            createdName = String.valueOf(created.get("name"));
            skills.setInstalledFrom(workspace, createdName, bundle.installedFrom());
            if (cancelled.get()) {
                cleanup(workspace, createdName);
                tasks.finish(taskId, "cancelled", null, Map.of(
                    "installed", false, "name", createdName,
                    "enabled", false, "source_url", bundle.sourceUrl(),
                    "installed_from", bundle.installedFrom()
                ));
                return;
            }
            tasks.finish(taskId, "completed", null, Map.of(
                "installed", true, "name", createdName,
                "enabled", tasks.get(taskId).enable(),
                "source_url", bundle.sourceUrl(),
                "installed_from", bundle.installedFrom()
            ));
        } catch (InstallCancelledException exception) {
            if (createdName != null) {
                cleanup(workspace, createdName);
            }
            tasks.finish(taskId, "cancelled", null, null);
        } catch (Exception exception) {
            tasks.finish(taskId, "failed", exception.getMessage(), null);
        }
    }

    private Bundle resolve(
        String bundleUrl,
        String version,
        AtomicBoolean cancelled
    ) throws IOException, InterruptedException {
        URI uri = URI.create(bundleUrl);
        String host = uri.getHost().toLowerCase();
        if (host.equals("clawhub.ai") || host.equals("www.clawhub.ai")) {
            return resolveClawHub(uri, version, cancelled);
        }
        if (host.equals("skills.sh") || host.equals("www.skills.sh")) {
            return resolveSkillsSh(uri, version, cancelled);
        }
        if (host.equals("github.com") || host.equals("www.github.com")) {
            return resolveGitHub(uri, version, cancelled);
        }
        if (host.equals("lobehub.com") || host.equals("www.lobehub.com") || host.equals("market.lobehub.com")) {
            return resolveLobeHub(uri, version, cancelled);
        }
        if (host.equals("modelscope.cn") || host.equals("www.modelscope.cn") || host.equals("platform.agentscope.io")) {
            return resolveArchiveHub(uri, version, cancelled);
        }
        ensureNotCancelled(cancelled);
        return parseBundle(fetchText(uri, cancelled), bundleUrl, "hub");
    }

    /**
     * skills.sh publishes a GitHub owner, repository and skill-directory
     * triplet. Resolve it through the same contents API path as a GitHub URL
     * so revisions, cancellation and package limits remain identical.
     */
    private Bundle resolveSkillsSh(URI source, String requestedVersion,
                                   AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        String[] spec = extractSkillsShSpec(source);
        if (spec == null) {
            throw new IllegalArgumentException("Invalid skills.sh URL format");
        }
        return resolveGitHubDirectory(
            spec[0], spec[1], spec[2], requestedVersion, "skills-sh", cancelled
        );
    }

    private Bundle resolveArchiveHub(URI source, String version, AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        String[] raw=source.getPath().split("/"); java.util.List<String> parts=new java.util.ArrayList<>();for(String part:raw)if(!part.isBlank())parts.add(part);
        if(parts.size()<3||!"skills".equals(parts.getFirst()))throw new IllegalArgumentException("Invalid skill archive URL");
        String owner=parts.get(1),name=parts.get(2),branch=version;
        if(branch==null||branch.isBlank()){branch="main";if(parts.size()>=6&&"archive".equals(parts.get(3))&&"zip".equals(parts.get(4)))branch=parts.get(5).replaceFirst("\\.zip$","");}
        String host=source.getHost().toLowerCase();String base=host.contains("modelscope")?"https://www.modelscope.cn":"https://platform.agentscope.io";
        String url=base+"/skills/"+URLEncoder.encode(owner,StandardCharsets.UTF_8)+"/"+URLEncoder.encode(name,StandardCharsets.UTF_8)+"/archive/zip/"+URLEncoder.encode(branch,StandardCharsets.UTF_8);
        Map<String,Object> files=new LinkedHashMap<>();
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fetchBytes(URI.create(url),cancelled)))){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){ensureNotCancelled(cancelled);if(entry.isDirectory())continue;if(files.size()>=MAX_PACKAGE_ENTRIES)throw new IllegalArgumentException("Archive has too many files");String path=entry.getName().replace('\\','/');if(path.startsWith("/")||path.contains("../"))throw new IllegalArgumentException("Unsafe path in archive");int slash=path.indexOf('/');if(slash<0)continue;byte[] bytes=readLimited(zip,cancelled);if(isText(bytes))files.put(path.substring(slash+1),new String(bytes,StandardCharsets.UTF_8));}
        }
        if(!files.containsKey("SKILL.md"))throw new IllegalStateException("Archive is missing SKILL.md");
        return fromFiles(name,files,url,host.contains("modelscope")?"modelscope":"agentscope");
    }

    private Bundle resolveLobeHub(URI source, String version, AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        String path=source.getPath().replaceAll("/+$",""); String id=path.substring(path.lastIndexOf('/')+1);
        if(id.isBlank())throw new IllegalArgumentException("Invalid LobeHub skill URL");
        String url="https://market.lobehub.com/api/v1/skills/"+URLEncoder.encode(id,StandardCharsets.UTF_8)+"/download";
        if(version!=null&&!version.isBlank())url+="?version="+URLEncoder.encode(version,StandardCharsets.UTF_8);
        Map<String,Object> files=new LinkedHashMap<>();
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fetchBytes(URI.create(url),cancelled)))){
            java.util.zip.ZipEntry entry; while((entry=zip.getNextEntry())!=null){ensureNotCancelled(cancelled);if(entry.isDirectory())continue;if(files.size()>=MAX_PACKAGE_ENTRIES)throw new IllegalArgumentException("LobeHub skill package has too many files");String name=entry.getName().replace('\\','/');if(name.startsWith("/")||name.contains("../"))throw new IllegalArgumentException("Unsafe path in LobeHub package");byte[] bytes=readLimited(zip,cancelled);if(!isText(bytes))continue;files.put(name,new String(bytes,StandardCharsets.UTF_8));}
        }
        if(!files.containsKey("SKILL.md")) {
            String prefix=null; for(String file:files.keySet()){int slash=file.indexOf('/');if(slash<0){prefix=null;break;}String candidate=file.substring(0,slash+1);if(prefix==null)prefix=candidate;else if(!prefix.equals(candidate)){prefix=null;break;}}
            if(prefix!=null&&files.containsKey(prefix+"SKILL.md")){Map<String,Object> normalized=new LinkedHashMap<>();for(Map.Entry<String,Object> entry:files.entrySet())normalized.put(entry.getKey().substring(prefix.length()),entry.getValue());files=normalized;}
        }
        if(!files.containsKey("SKILL.md"))throw new IllegalStateException("LobeHub skill package is missing SKILL.md");
        return fromFiles(id,files,url,"lobehub");
    }

    private Bundle resolveGitHub(URI source, String requestedVersion,
                                 AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        String[] parts = source.getPath().replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid GitHub skill URL");
        String owner = parts[0], repo = parts[1], ref = requestedVersion;
        String path = "";
        if (parts.length >= 4 && ("tree".equals(parts[2]) || "blob".equals(parts[2]))) {
            if (ref == null || ref.isBlank()) ref = parts[3];
            path = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
            if (path.endsWith("/SKILL.md")) path = path.substring(0, path.length() - 9);
        }
        return resolveGitHubDirectory(owner, repo, path, ref, "github", cancelled);
    }

    private Bundle resolveGitHubDirectory(String owner, String repo, String path,
                                          String requestedVersion, String origin,
                                          AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        String ref = requestedVersion;
        if (ref == null || ref.isBlank()) {
            Map<String,Object> repository = object(new ObjectMapper().readValue(fetchText(
                URI.create("https://api.github.com/repos/" + owner + "/" + repo), cancelled
            ), new TypeReference<Object>() {}));
            ref = text(repository.get("default_branch"));
            if (ref.isBlank()) ref = "main";
        }
        Map<String,Object> files = new LinkedHashMap<>();
        githubCollect(owner, repo, path, ref, "", files, cancelled);
        if (!files.containsKey("SKILL.md")) throw new IllegalStateException("GitHub skill directory is missing SKILL.md");
        return fromFiles(path.isBlank() ? repo : path.substring(path.lastIndexOf('/') + 1), files,
            "https://github.com/" + owner + "/" + repo, origin);
    }

    private void githubCollect(String owner, String repo, String path, String ref,
                               String relative, Map<String,Object> files,
                               AtomicBoolean cancelled) throws IOException, InterruptedException {
        ensureNotCancelled(cancelled);
        if (files.size() >= MAX_PACKAGE_ENTRIES) {
            throw new IllegalArgumentException("GitHub skill package has too many files");
        }
        String encoded = path.isBlank() ? "" : "/" + path.replace(" ", "%20");
        String endpoint = "https://api.github.com/repos/" + owner + "/" + repo + "/contents" + encoded + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        Object response = new ObjectMapper().readValue(fetchText(URI.create(endpoint), cancelled), new TypeReference<Object>() {});
        if (response instanceof Map<?,?> raw) {
            Map<String,Object> entry = asMap(raw);
            if (!"file".equals(text(entry.get("type")))) throw new IllegalStateException("Unexpected GitHub skill response");
            String name = relative.isBlank() ? text(entry.get("name")) : relative;
            files.put(name, fetchText(URI.create(text(entry.get("download_url"))), cancelled)); return;
        }
        if (!(response instanceof Iterable<?> entries)) throw new IllegalStateException("Unexpected GitHub skill response");
        for (Object raw : entries) { Map<String,Object> entry=asMap(raw); String name=text(entry.get("name")); String child=relative.isBlank()?name:relative+"/"+name; if("dir".equals(text(entry.get("type")))) githubCollect(owner,repo,text(entry.get("path")),ref,child,files,cancelled); else if("file".equals(text(entry.get("type")))) { if(files.size()>=MAX_PACKAGE_ENTRIES)throw new IllegalArgumentException("GitHub skill package has too many files"); files.put(child,fetchText(URI.create(text(entry.get("download_url"))),cancelled)); } }
    }

    private Bundle resolveClawHub(
        URI source,
        String requestedVersion,
        AtomicBoolean cancelled
    ) throws IOException, InterruptedException {
        String path = source.getPath().replaceAll("/+$", "");
        String slug = path.substring(path.lastIndexOf('/') + 1);
        if (slug.isBlank() || !slug.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid ClawHub skill URL");
        }
        String detailUrl = CLAWHUB + "/api/v1/skills/" + slug;
        Map<String, Object> detail = object(fetchText(
            URI.create(detailUrl), cancelled
        ));
        Map<String, Object> skill = asMap(detail.get("skill"));
        String actualSlug = text(skill.get("slug"));
        if (actualSlug.isBlank()) {
            actualSlug = slug;
        }
        Map<String, Object> current = asMap(detail.get("version"));
        String version = requestedVersion == null || requestedVersion.isBlank()
            ? text(current.get("version")) : requestedVersion;
        Map<String, Object> files = new LinkedHashMap<>();
        Object rawFiles = current.get("files");
        if (rawFiles instanceof Iterable<?> entries) {
            for (Object entry : entries) {
                ensureNotCancelled(cancelled);
                String file = text(asMap(entry).get("path"));
                if (file.isBlank()) {
                    continue;
                }
                String fileUrl = CLAWHUB + "/api/v1/skills/"
                    + actualSlug + "/file?path=" + URLEncoder.encode(
                        file, StandardCharsets.UTF_8
                    );
                if (!version.isBlank()) {
                    fileUrl += "&version=" + URLEncoder.encode(
                        version, StandardCharsets.UTF_8
                    );
                }
                files.put(file, fetchText(URI.create(fileUrl), cancelled));
            }
        }
        if (files.isEmpty()) {
            String content = text(detail.get("content"));
            if (content.isBlank()) {
                content = text(detail.get("skill_md"));
            }
            if (!content.isBlank()) {
                files.put("SKILL.md", content);
            }
        }
        if (!files.containsKey("SKILL.md")) {
            throw new IllegalStateException("ClawHub bundle is missing SKILL.md");
        }
        String name = text(skill.get("displayName"));
        return fromFiles(name.isBlank() ? actualSlug : name, files,
            detailUrl, "clawhub");
    }

    private String fetchText(URI uri, AtomicBoolean cancelled)
        throws IOException, InterruptedException {
        ensureNotCancelled(cancelled);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<InputStream> response = http.send(
            request, HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                "Skill Hub returned HTTP " + response.statusCode()
            );
        }
        try (InputStream input = response.body()) { return new String(readLimited(input, cancelled), StandardCharsets.UTF_8); }
    }

    private byte[] fetchBytes(URI uri, AtomicBoolean cancelled) throws IOException, InterruptedException {
        ensureNotCancelled(cancelled); HttpResponse<InputStream> response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()>=400)throw new IllegalStateException("Skill Hub returned HTTP "+response.statusCode()); try(InputStream input=response.body()){return readLimited(input,cancelled);}
    }

    private static byte[] readLimited(
        InputStream input,
        AtomicBoolean cancelled
    ) throws IOException {
        try (var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                ensureNotCancelled(cancelled);
                if (output.size() + size > MAX_BUNDLE_BYTES) {
                    throw new IllegalArgumentException(
                        "Hub bundle exceeds 200 MB"
                    );
                }
                output.write(buffer, 0, size);
            }
            return output.toByteArray();
        }
    }

    private static boolean isText(byte[] bytes){for(byte value:bytes)if(value==0)return false;return true;}

    static Bundle parseBundle(String json, String sourceUrl, String origin)
        throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payload = object(mapper.readValue(
            json, new TypeReference<Object>() {}
        ));
        Map<String, Object> nested = asMap(payload.get("skill"));
        if (!hasContent(payload) && hasContent(nested)) {
            payload = nested;
        }
        String content = firstText(payload, "content", "skill_md", "skillMd");
        Map<String, Object> files = asMap(payload.get("files"));
        if (content.isBlank()) {
            content = text(files.get("SKILL.md"));
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("Hub bundle missing SKILL.md content");
        }
        String name = text(payload.get("name"));
        if (name.isBlank()) {
            name = text(SkillWorkspaceService.parseFrontmatter(content).get("name"));
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Hub bundle missing skill name");
        }
        Map<String, Object> references = tree(payload.get("references"));
        Map<String, Object> scripts = tree(payload.get("scripts"));
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            if (!(entry.getValue() instanceof String value)
                || entry.getKey().equals("SKILL.md")) {
                continue;
            }
            if (entry.getKey().startsWith("references/")) {
                insert(references, entry.getKey().substring(11), value);
            } else if (entry.getKey().startsWith("scripts/")) {
                insert(scripts, entry.getKey().substring(8), value);
            } else {
                insert(extra, entry.getKey(), value);
            }
        }
        return new Bundle(name, content, references, scripts, extra,
            sourceUrl, origin);
    }

    private static Bundle fromFiles(
        String fallbackName,
        Map<String, Object> files,
        String sourceUrl,
        String origin
    ) {
        String content = text(files.get("SKILL.md"));
        String name = text(SkillWorkspaceService.parseFrontmatter(content).get("name"));
        if (name.isBlank()) {
            name = fallbackName;
        }
        Map<String, Object> references = new LinkedHashMap<>();
        Map<String, Object> scripts = new LinkedHashMap<>();
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            if (entry.getKey().equals("SKILL.md")) {
                continue;
            }
            String file = entry.getKey();
            if (file.startsWith("references/")) {
                insert(references, file.substring(11), entry.getValue());
            } else if (file.startsWith("scripts/")) {
                insert(scripts, file.substring(8), entry.getValue());
            } else {
                insert(extra, file, entry.getValue());
            }
        }
        return new Bundle(name, content, references, scripts, extra,
            sourceUrl, origin);
    }

    private static void insert(
        Map<String, Object> root,
        String path,
        Object value
    ) {
        String[] parts = path.split("/");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Unsafe path in Hub bundle");
            }
            if (index == parts.length - 1) {
                current.put(part, value);
            } else {
                current = asMap(current.computeIfAbsent(
                    part, ignored -> new LinkedHashMap<String, Object>()
                ));
            }
        }
    }

    private static Map<String, Object> tree(Object source) {
        if (!(source instanceof Map<?, ?>)) {
            return new LinkedHashMap<>();
        }
        return asMap(source);
    }

    private static boolean hasContent(Map<String, Object> source) {
        return !firstText(source, "content", "skill_md", "skillMd").isBlank()
            || !text(asMap(source.get("files")).get("SKILL.md")).isBlank();
    }

    private static Map<String, Object> object(Object source) {
        if (!(source instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Hub bundle is not a JSON object");
        }
        return asMap(source);
    }

    private static Map<String, Object> asMap(Object source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    private static String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String[] extractSkillsShSpec(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!host.equals("skills.sh") && !host.equals("www.skills.sh")) {
            return null;
        }
        String[] path = uri.getPath().split("/");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : path) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        if (parts.size() < 3) {
            return null;
        }
        return new String[]{parts.get(0), parts.get(1), parts.get(2)};
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "bundle_url must be a valid http(s) URL"
            );
        }
    }

    private static void ensureNotCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new InstallCancelledException();
        }
    }

    record Bundle(
        String name,
        String content,
        Map<String, Object> references,
        Map<String, Object> scripts,
        Map<String, Object> extraFiles,
        String sourceUrl,
        String installedFrom
    ) {}

    private static final class InstallCancelledException
        extends RuntimeException {}

    private void cleanup(Path workspace, String name) {
        skills.setEnabled(workspace, name, false);
        skills.delete(workspace, name);
    }
}
