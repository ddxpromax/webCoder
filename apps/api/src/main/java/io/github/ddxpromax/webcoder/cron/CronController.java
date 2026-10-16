package io.github.ddxpromax.webcoder.cron;

import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo cron management API; agent-scoped aliases select the path agent. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CronController {
    private final CronService cron;
    private final ChatService chats;
    public CronController(CronService cron, ChatService chats) { this.cron = cron; this.chats = chats; }

    @GetMapping("/cron/dispatch-targets")
    public Map<String, Object> targets(@RequestParam(required = false) String channel, @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "500") int limit, @RequestParam(required = false, name = "agent_id") String agentId,
        @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return dispatchTargets(agent(headerAgent, agentId), channel, keyword, limit); }
    @GetMapping("/cron/jobs") public List<Map<String, Object>> list(@RequestParam(required = false, name = "agent_id") String agentId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return cron.list(agent(headerAgent, agentId)); }
    @PostMapping("/cron/jobs") public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return createFor(agent(headerAgent, string(body.get("agent_id"))), body); }
    @GetMapping("/cron/jobs/{jobId}") public ResponseEntity<?> get(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return found(cron.get(agent(headerAgent, null), jobId)); }
    @PutMapping("/cron/jobs/{jobId}") public ResponseEntity<?> replace(@PathVariable String jobId, @RequestBody Map<String, Object> body, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { try { return ResponseEntity.ok(cron.replace(agent(headerAgent, string(body.get("agent_id"))), jobId, body)); } catch (CronService.MismatchException e) { return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage())); } catch (IllegalArgumentException e) { return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage())); } }
    @DeleteMapping("/cron/jobs/{jobId}") public ResponseEntity<?> delete(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return cron.delete(agent(headerAgent, null), jobId) ? ResponseEntity.ok(Map.of("deleted", true)) : missing(); }
    @PostMapping("/cron/jobs/{jobId}/pause") public ResponseEntity<?> pause(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return cron.pause(agent(headerAgent, null), jobId) ? ResponseEntity.ok(Map.of("paused", true)) : missing(); }
    @PostMapping("/cron/jobs/{jobId}/resume") public ResponseEntity<?> resume(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return cron.resume(agent(headerAgent, null), jobId) ? ResponseEntity.ok(Map.of("resumed", true)) : missing(); }
    @PostMapping("/cron/jobs/{jobId}/run") public ResponseEntity<?> run(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return cron.run(agent(headerAgent, null), jobId) ? ResponseEntity.ok(Map.of("started", true)) : missing(); }
    @GetMapping("/cron/jobs/{jobId}/state") public ResponseEntity<?> state(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { return found(cron.state(agent(headerAgent, null), jobId)); }
    @GetMapping("/cron/jobs/{jobId}/history") public ResponseEntity<?> history(@PathVariable String jobId, @RequestHeader(value = "X-Agent-Id", required = false) String headerAgent) { String effective = agent(headerAgent, null); return cron.get(effective, jobId) == null ? missing() : ResponseEntity.ok(cron.history(effective, jobId)); }

    @GetMapping("/agents/{agentId}/cron/dispatch-targets") public Map<String, Object> agentTargets(@PathVariable String agentId, @RequestParam(required = false) String channel, @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "500") int limit) { return dispatchTargets(agentId, channel, keyword, limit); }
    @GetMapping("/agents/{agentId}/cron/jobs") public List<Map<String, Object>> agentList(@PathVariable String agentId) { return cron.list(agentId); }
    @PostMapping("/agents/{agentId}/cron/jobs") public ResponseEntity<?> agentCreate(@PathVariable String agentId, @RequestBody Map<String, Object> body) { return createFor(agentId, body); }
    @GetMapping("/agents/{agentId}/cron/jobs/{jobId}") public ResponseEntity<?> agentGet(@PathVariable String agentId, @PathVariable String jobId) { return found(cron.get(agentId, jobId)); }
    @PutMapping("/agents/{agentId}/cron/jobs/{jobId}") public ResponseEntity<?> agentReplace(@PathVariable String agentId, @PathVariable String jobId, @RequestBody Map<String, Object> body) { try { return ResponseEntity.ok(cron.replace(agentId, jobId, body)); } catch (CronService.MismatchException e) { return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage())); } catch (IllegalArgumentException e) { return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage())); } }
    @DeleteMapping("/agents/{agentId}/cron/jobs/{jobId}") public ResponseEntity<?> agentDelete(@PathVariable String agentId, @PathVariable String jobId) { return cron.delete(agentId, jobId) ? ResponseEntity.ok(Map.of("deleted", true)) : missing(); }
    @PostMapping("/agents/{agentId}/cron/jobs/{jobId}/pause") public ResponseEntity<?> agentPause(@PathVariable String agentId, @PathVariable String jobId) { return cron.pause(agentId, jobId) ? ResponseEntity.ok(Map.of("paused", true)) : missing(); }
    @PostMapping("/agents/{agentId}/cron/jobs/{jobId}/resume") public ResponseEntity<?> agentResume(@PathVariable String agentId, @PathVariable String jobId) { return cron.resume(agentId, jobId) ? ResponseEntity.ok(Map.of("resumed", true)) : missing(); }
    @PostMapping("/agents/{agentId}/cron/jobs/{jobId}/run") public ResponseEntity<?> agentRun(@PathVariable String agentId, @PathVariable String jobId) { return cron.run(agentId, jobId) ? ResponseEntity.ok(Map.of("started", true)) : missing(); }
    @GetMapping("/agents/{agentId}/cron/jobs/{jobId}/state") public ResponseEntity<?> agentState(@PathVariable String agentId, @PathVariable String jobId) { return found(cron.state(agentId, jobId)); }
    @GetMapping("/agents/{agentId}/cron/jobs/{jobId}/history") public ResponseEntity<?> agentHistory(@PathVariable String agentId, @PathVariable String jobId) { return cron.get(agentId, jobId) == null ? missing() : ResponseEntity.ok(cron.history(agentId, jobId)); }

    private ResponseEntity<?> createFor(String agentId, Map<String, Object> body) { try { return ResponseEntity.ok(cron.create(agentId, body)); } catch (IllegalArgumentException e) { return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage())); } }
    private Map<String, Object> dispatchTargets(String agentId, String channel, String keyword, int limit) { String needle = keyword == null ? "" : keyword.toLowerCase(); Map<String, Map<String, Object>> dedupe = new LinkedHashMap<>(); for (ChatEntity chat : chats.listByAgent(agentId, null)) { String actualChannel = chat.getChannel() == null ? "console" : chat.getChannel(); if (channel != null && !channel.isBlank() && !channel.equals(actualChannel)) continue; String user = chat.getUserId() == null ? "" : chat.getUserId(); String session = chat.getSessionId() == null ? "" : chat.getSessionId(); if (!needle.isBlank() && !(actualChannel + " " + user + " " + session).toLowerCase().contains(needle)) continue; dedupe.put(actualChannel + "\u0000" + user + "\u0000" + session, Map.of("channel", actualChannel, "user_id", user, "session_id", session)); if (dedupe.size() >= Math.max(1, limit)) break; } List<String> channels = new ArrayList<>(); for (Map<String, Object> value : dedupe.values()) { String itemChannel = String.valueOf(value.get("channel")); if (!channels.contains(itemChannel)) channels.add(itemChannel); } channels.sort(String::compareTo); if (!channels.contains("console")) channels.addFirst("console"); return Map.of("channels", channels, "items", new ArrayList<>(dedupe.values())); }
    private ResponseEntity<?> found(Object value) { return value == null ? missing() : ResponseEntity.ok(value); }
    private ResponseEntity<Map<String, String>> missing() { return ResponseEntity.status(404).body(Map.of("detail", "job not found")); }
    private String agent(String first, String second) { return first != null && !first.isBlank() ? first : (second == null || second.isBlank() ? "default" : second); }
    private String string(Object value) { return value instanceof String text ? text : null; }
}
