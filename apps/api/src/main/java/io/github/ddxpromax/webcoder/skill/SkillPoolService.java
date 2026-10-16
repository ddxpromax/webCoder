package io.github.ddxpromax.webcoder.skill;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.github.ddxpromax.webcoder.agent.JsonFileStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Majo's reusable local skill pool at {@code data/majo/skill_pool}. */
@Service
public class SkillPoolService {
    private final Path root;
    private final JsonFileStore files;
    private final SkillWorkspaceService workspaceSkills;
    private Map<String, Object> activeManifest;
    private Map<String, Object> activeEntries;

    public SkillPoolService(MajoWorkingDirectory working, JsonFileStore files,
                            SkillWorkspaceService workspaceSkills) {
        this.root = working.resolve("skill_pool");
        this.files = files;
        this.workspaceSkills = workspaceSkills;
    }

    public synchronized List<Map<String, Object>> list() {
        reconcile();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : entries().keySet().stream().sorted().toList()) {
            Path directory = root.resolve(name);
            if (!Files.isRegularFile(directory.resolve("SKILL.md"))) continue;
            Map<String, Object> entry = map(entries().get(name));
            String content = text(directory.resolve("SKILL.md"));
            Map<String, Object> frontmatter = SkillWorkspaceService.parseFrontmatter(content);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", name);
            spec.put("description", String.valueOf(frontmatter.getOrDefault("description", "")));
            spec.put("content", content);
            spec.put("source", entry.getOrDefault("source", "customized"));
            spec.put("tags", entry.getOrDefault("tags", List.of()));
            spec.put("config", entry.getOrDefault("config", Map.of()));
            spec.put("installed_from", entry.getOrDefault("installed_from", ""));
            spec.put("auto_update", Boolean.TRUE.equals(entry.get("auto_update")));
            spec.put("last_updated", lastUpdated(directory.resolve("SKILL.md")));
            result.add(spec);
        }
        return result;
    }

    public synchronized Map<String, Object> create(String name, String content,
                                                    Map<String, Object> references,
                                                    Map<String, Object> scripts,
                                                    Map<String, Object> config,
                                                    String installedFrom) {
        Path staged = null;
        try {
            staged = Files.createTempDirectory(root.getParent(), "majo-pool-");
            Map<String, Object> created = workspaceSkills.create(staged, name, content,
                references, scripts, null, config, false);
            if (!Boolean.TRUE.equals(created.get("success"))) return created;
            String normalized = String.valueOf(created.get("name"));
            reconcile();
            if (Files.exists(root.resolve(normalized))) return Map.of("success", false, "reason", "conflict");
            copyTree(staged.resolve("skills").resolve(normalized), root.resolve(normalized));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", "customized"); entry.put("tags", List.of());
            entry.put("config", config == null ? Map.of() : config);
            if (installedFrom != null && !installedFrom.isBlank()) entry.put("installed_from", installedFrom);
            entries().put(normalized, entry); save();
            return Map.of("success", true, "name", normalized);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create pool skill", exception);
        } finally { deleteTree(staged); }
    }

    public synchronized Map<String, Object> upload(Path workspace, String name,
                                                     boolean overwrite, boolean previewOnly) {
        reconcile();
        workspaceSkills.reconcile(workspace);
        Path source = workspace.resolve("skills").resolve(name).normalize();
        if (!source.startsWith(workspace.resolve("skills").toAbsolutePath().normalize()) || !Files.isRegularFile(source.resolve("SKILL.md"))) return Map.of("success", false, "reason", "not_found");
        String normalized = checkedName(name);
        if (Files.exists(root.resolve(normalized)) && !overwrite) return Map.of("success", false, "reason", "conflict");
        if (previewOnly) return Map.of("success", true, "name", normalized);
        scan(normalized, source);
        replaceCopy(source, root.resolve(normalized));
        Map<String, Object> entry = map(entries().get(normalized));
        entry.put("source", "customized"); entry.put("installed_from", "workspace");
        entries().put(normalized, entry); save();
        return Map.of("success", true, "name", normalized);
    }

    public synchronized Map<String, Object> download(Path workspace, String name,
                                                       boolean overwrite, boolean previewOnly) {
        reconcile(); String normalized = checkedName(name); Path source = root.resolve(normalized);
        if (!Files.isRegularFile(source.resolve("SKILL.md"))) return Map.of("success", false, "reason", "not_found");
        Path target = workspace.resolve("skills").resolve(normalized);
        if (Files.exists(target) && !overwrite) return Map.of("success", false, "reason", "conflict");
        if (previewOnly) return Map.of("success", true, "name", normalized);
        scan(normalized, source); replaceCopy(source, target);
        workspaceSkills.reconcile(workspace); workspaceSkills.setEnabled(workspace, normalized, true);
        workspaceSkills.setInstalledFrom(workspace, normalized, "pool");
        return Map.of("success", true, "name", normalized);
    }

    public synchronized boolean delete(String name) {
        reconcile();
        String normalized = checkedName(name); if (!entries().containsKey(normalized)) return false;
        deleteTree(root.resolve(normalized)); entries().remove(normalized); save(); return true;
    }
    public synchronized boolean tags(String name, List<String> tags) { reconcile(); return update(name, "tags", tags == null ? List.of() : tags); }
    public synchronized boolean config(String name, Map<String, Object> config) { reconcile(); return update(name, "config", config == null ? Map.of() : config); }
    public synchronized boolean autoUpdate(String name, boolean enabled, List<String> targets) {
        reconcile();
        String normalized = checkedName(name); Map<String,Object> entry=map(entries().get(normalized)); if(entry.isEmpty()) return false;
        entry.put("auto_update", enabled); if(targets == null || targets.isEmpty()) entry.remove("auto_update_targets"); else entry.put("auto_update_targets", targets); entries().put(normalized,entry); save(); return true;
    }

    private boolean update(String name, String key, Object value) { String normalized=checkedName(name); Map<String,Object> entry=map(entries().get(normalized)); if(entry.isEmpty()) return false; entry.put(key,value); entries().put(normalized,entry); save(); return true; }
    private void reconcile() { try { Files.createDirectories(root); load(); Map<String,Object> entries=entries(); try(var dirs=Files.list(root)){ for(Path dir:dirs.toList()){String name=dir.getFileName().toString(); if(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(dir.resolve("SKILL.md"))&&!entries.containsKey(name)) entries.put(name,new LinkedHashMap<>(Map.of("source","customized","tags",List.of(),"config",Map.of()))); } } save(); } catch(IOException e){throw new IllegalStateException("Could not reconcile skill pool",e);} }
    private void load(){ activeManifest=files.read(root.resolve("skill.json"), new LinkedHashMap<>(Map.of("schema_version","pool-skill-manifest.v1","skills",new LinkedHashMap<>()))); Object raw=activeManifest.get("skills"); activeEntries=map(raw); activeManifest.put("skills",activeEntries); }
    private Map<String,Object> entries(){ if(activeEntries==null)load(); return activeEntries; }
    private void save(){ activeManifest.put("skills",entries()); files.writeAtomic(root.resolve("skill.json"),activeManifest); }
    private static Map<String,Object> map(Object raw){Map<String,Object> out=new LinkedHashMap<>();if(raw instanceof Map<?,?> in)in.forEach((k,v)->out.put(String.valueOf(k),v));return out;}
    private static String checkedName(String name){if(name==null||!name.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid skill name");return name;}
    private static String text(Path path){try{return Files.readString(path,StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Could not read pool skill",e);}}
    private static String lastUpdated(Path path){try{return Files.getLastModifiedTime(path).toInstant().toString();}catch(IOException e){return "";}}
    private static void scan(String name, Path dir){try{Map<String,String> files=new LinkedHashMap<>();try(var paths=Files.walk(dir)){for(Path path:paths.filter(Files::isRegularFile).toList()){String relative=dir.relativize(path).toString().replace('\\','/');if(!relative.equals("SKILL.md"))files.put(relative,Files.readString(path));}}var result=SkillSecurityScanner.scan(name,text(dir.resolve("SKILL.md")),files);if(result.verdict()==SkillSecurityScanner.Verdict.DANGEROUS)throw new IllegalArgumentException("Skill security scan failed: "+result.reportText());}catch(IOException e){throw new IllegalStateException("Could not scan pool skill",e);}}
    private static void replaceCopy(Path source,Path target){Path stage=null;try{Files.createDirectories(target.getParent());stage=Files.createTempDirectory(target.getParent(),".pool-stage-");copyTree(source,stage.resolve(target.getFileName()));deleteTree(target);Files.move(stage.resolve(target.getFileName()),target,StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new IllegalStateException("Could not copy pool skill",e);}finally{deleteTree(stage);}}
    private static void copyTree(Path source,Path target)throws IOException{try(var paths=Files.walk(source)){for(Path path:paths.toList()){if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Symlinks are not allowed in skills");Path destination=target.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else{Files.createDirectories(destination.getParent());Files.copy(path,destination,StandardCopyOption.REPLACE_EXISTING);}}}}
    private static void deleteTree(Path path){if(path==null||!Files.exists(path))return;try(var paths=Files.walk(path)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(item->{try{Files.deleteIfExists(item);}catch(IOException ignored){}});}catch(IOException ignored){}}
}
