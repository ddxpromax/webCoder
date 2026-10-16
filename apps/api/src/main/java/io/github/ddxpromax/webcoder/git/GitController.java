package io.github.ddxpromax.webcoder.git;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo workspace Git endpoints, scoped by X-Agent-Id or the agent query parameter. */
@RestController
@RequestMapping("/api/workspace/git")
@CrossOrigin(origins = "*")
public class GitController {
    private final GitService git;
    private final AgentStore agents;

    public GitController(GitService git, AgentStore agents) {
        this.git = git;
        this.agents = agents;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        try { return ResponseEntity.ok(git.status(workspace(request))); }
        catch (Exception exception) { return failure(exception); }
    }

    @GetMapping("/branches")
    public ResponseEntity<?> branches(HttpServletRequest request) {
        try { return ResponseEntity.ok(git.branches(workspace(request))); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Map<String, Object> body,
        HttpServletRequest request) {
        String branch = String.valueOf(body.getOrDefault("branch", "")).trim();
        if (branch.isBlank()) return ResponseEntity.badRequest().body(Map.of("detail", "branch is required"));
        try { return ResponseEntity.ok(git.checkout(workspace(request), branch, Boolean.TRUE.equals(body.get("create")))); }
        catch (Exception exception) { return failure(exception); }
    }

    @GetMapping("/diff")
    public ResponseEntity<?> diff(@RequestParam(required = false) String path,
        @RequestParam(defaultValue = "false") boolean staged,
        @RequestParam(defaultValue = "false") boolean untracked,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.diff(workspace(request), path, staged, untracked)); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/stage")
    public ResponseEntity<?> stage(@RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.stage(workspace(request), paths(body))); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/unstage")
    public ResponseEntity<?> unstage(@RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.unstage(workspace(request), paths(body))); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody Map<String, Object> body,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.commit(workspace(request), String.valueOf(body.getOrDefault("message", "")))); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/discard")
    public ResponseEntity<?> discard(@RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.discard(workspace(request), paths(body))); }
        catch (Exception exception) { return failure(exception); }
    }

    @PostMapping("/revert")
    public ResponseEntity<?> revert(@RequestBody Map<String, Object> body,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.revert(workspace(request), String.valueOf(body.getOrDefault("commit_hash", "")))); }
        catch (Exception exception) { return failure(exception); }
    }

    @GetMapping("/commit-diff")
    public ResponseEntity<?> commitDiff(@RequestParam("commit_hash") String hash,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.commitDiff(workspace(request), hash)); }
        catch (Exception exception) { return failure(exception); }
    }

    @GetMapping("/log")
    public ResponseEntity<?> log(@RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request) {
        try { return ResponseEntity.ok(git.log(workspace(request), limit)); }
        catch (Exception exception) { return failure(exception); }
    }

    private Path workspace(HttpServletRequest request) {
        String id = request.getHeader("X-Agent-Id");
        if (id == null || id.isBlank()) id = request.getParameter("agent");
        return agents.workspaceDirForAgent(id == null || id.isBlank()
            ? AgentStore.DEFAULT_AGENT_ID : id);
    }

    @SuppressWarnings("unchecked")
    private List<String> paths(Map<String, Object> body) {
        if (body == null || !(body.get("paths") instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    private ResponseEntity<Object> failure(Exception exception) {
        String detail = exception.getMessage() == null ? String.valueOf(exception) : exception.getMessage();
        if (detail.toLowerCase().contains("not a git repository") || detail.toLowerCase().contains("no git directory")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", "Not a git repository"));
        }
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }
}
