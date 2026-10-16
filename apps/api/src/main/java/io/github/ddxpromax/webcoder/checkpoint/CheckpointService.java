package io.github.ddxpromax.webcoder.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

/** Isolated Git object store: snapshots capture workspace files without touching its own Git history. */
@Service
public class CheckpointService {
    private final ObjectMapper mapper;
    public CheckpointService(ObjectMapper mapper) { this.mapper = mapper; }
    private Path root(Path workspace) { return workspace.resolve(".checkpoints"); }
    private Path gitDir(Path workspace) { return root(workspace).resolve("git"); }
    private Path heads(Path workspace) { return root(workspace).resolve("heads.json"); }
    private Repository repo(Path workspace) throws IOException {
        Path dir = gitDir(workspace); Files.createDirectories(dir.getParent());
        if (!Files.exists(dir.resolve("HEAD"))) try (Git ignored = Git.init().setDirectory(workspace.toFile()).setGitDir(dir.toFile()).call()) { } catch (Exception e) { throw new IOException("Could not initialize checkpoint repository", e); }
        return new FileRepositoryBuilder().setGitDir(dir.toFile()).setWorkTree(workspace.toFile()).build();
    }
    public synchronized Map<String,Object> snapshot(Path workspace, String sessionId, String userId, String channel, String name) throws IOException {
        String session = safe(channel + "-" + userId + "-" + sessionId); try (Repository repo = repo(workspace); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call(); var commit = git.commit().setAll(true).setAllowEmpty(true).setMessage("checkpoint " + name).setAuthor("majo", "majo@local").call();
            String ref = "refs/snap/" + session + "/" + safe(name.isBlank() ? "snapshot" : name); while (repo.findRef(ref) != null) ref += "-next"; putRef(repo, ref, commit.getId()); Map<String,String> heads = loadHeads(workspace); heads.put(session, commit.getName()); saveHeads(workspace, heads); return node(workspace, ref, commit.getName(), session, heads);
        } catch (Exception e) { throw new IOException("Checkpoint commit failed", e); }
    }
    public List<Map<String,Object>> graph(Path workspace, int limit) throws IOException {
        Map<String,String> heads = loadHeads(workspace); List<Map<String,Object>> result = new ArrayList<>(); try (Repository repo = repo(workspace)) { for (var ref : repo.getRefDatabase().getRefsByPrefix("refs/auto/", "refs/snap/", "refs/pre-restore/")) result.add(node(workspace, ref.getName(), ref.getObjectId().getName(), session(ref.getName()), heads)); } result.sort((a,b) -> Long.compare((long)b.get("timestamp_ms"), (long)a.get("timestamp_ms"))); return result.subList(0, Math.min(Math.max(1, limit), result.size()));
    }
    public List<String> changedPaths(Path workspace, String commit) throws IOException { try (Repository repo = repo(workspace); RevWalk walk = new RevWalk(repo)) { ObjectId target = resolve(repo, commit), head = repo.resolve("HEAD"); if (head == null) return List.of(); try (Git git = new Git(repo); var reader = repo.newObjectReader()) { var oldTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser(); var newTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser(); oldTree.reset(reader, walk.parseTree(target)); newTree.reset(reader, walk.parseTree(head)); return git.diff().setOldTree(oldTree).setNewTree(newTree).call().stream().map(d -> d.getNewPath()).distinct().toList(); } } catch (Exception e) { throw new IOException("Could not compare checkpoints", e); } }
    public synchronized List<String> restore(Path workspace, String commit, List<String> paths) throws IOException { List<String> changes = changedPaths(workspace, commit); try (Repository repo = repo(workspace); Git git = new Git(repo)) { ObjectId target = resolve(repo, commit), head = repo.resolve("HEAD"); if (head != null) putRef(repo, "refs/pre-restore/" + System.currentTimeMillis(), head); if (paths == null || paths.isEmpty()) git.checkout().setName(target.getName()).setForced(true).call(); else for (String path : paths) git.checkout().setStartPoint(target.getName()).addPath(path).call(); } catch (Exception e) { throw new IOException("Could not restore checkpoint", e); } return paths == null || paths.isEmpty() ? changes : paths; }
    public List<String> gc(Path workspace, boolean dryRun, int keep) throws IOException { List<Map<String,Object>> all = graph(workspace, 500); List<String> deleted = new ArrayList<>(); try (Repository repo = repo(workspace)) { for (int i = Math.max(0, keep); i < all.size(); i++) { String ref = (String) all.get(i).get("ref"); if (Boolean.TRUE.equals(all.get(i).get("is_head"))) continue; deleted.add(ref); if (!dryRun) { RefUpdate update = repo.updateRef(ref); update.setForceUpdate(true); update.delete(); } } } return deleted; }
    public void reset(Path workspace) throws IOException { try (Repository repo = repo(workspace)) { for (var ref : repo.getRefDatabase().getRefsByPrefix("refs/auto/", "refs/snap/", "refs/pre-restore/")) { RefUpdate update = repo.updateRef(ref.getName()); update.setForceUpdate(true); update.delete(); } } Files.deleteIfExists(heads(workspace)); }
    private Map<String,Object> node(Path workspace, String ref, String commit, String session, Map<String,String> heads) throws IOException { Map<String,Object> item = new LinkedHashMap<>(); item.put("ref", ref); item.put("kind", ref.split("/")[1]); item.put("commit", commit); item.put("sha", commit.substring(0, 12)); item.put("name", ref.substring(ref.lastIndexOf('/') + 1)); item.put("session_key", session); item.put("is_head", commit.equals(heads.get(session))); try (Repository repo = repo(workspace); RevWalk walk = new RevWalk(repo)) { item.put("timestamp_ms", walk.parseCommit(resolve(repo, commit)).getCommitTime() * 1000L); } return item; }
    private ObjectId resolve(Repository repo, String commit) throws IOException { ObjectId id = repo.resolve(commit); if (id == null) throw new IllegalArgumentException("checkpoint not found"); return id; }
    private void putRef(Repository repo, String name, ObjectId id) throws IOException { RefUpdate update = repo.updateRef(name); update.setNewObjectId(id); update.setForceUpdate(true); update.update(); }
    private Map<String,String> loadHeads(Path workspace) { try { return Files.isRegularFile(heads(workspace)) ? mapper.readValue(Files.readString(heads(workspace)), new TypeReference<LinkedHashMap<String,String>>() {}) : new LinkedHashMap<>(); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private void saveHeads(Path workspace, Map<String,String> value) throws IOException { Files.createDirectories(root(workspace)); mapper.writeValue(heads(workspace).toFile(), value); }
    private String session(String ref) { String[] parts = ref.split("/"); return parts.length > 2 ? parts[2] : "default"; }
    private String safe(String value) { String clean = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9._-]+", "-"); return clean.isBlank() ? "default" : clean; }
}
