package io.github.ddxpromax.webcoder.inbox;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console inbox endpoints retain Majo's event and trace contract. */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class InboxController {
    private final InboxService inbox;

    public InboxController(InboxService inbox) { this.inbox = inbox; }

    @GetMapping("/console/inbox/events")
    public Map<String, Object> list(
        @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
        @RequestParam(required = false, name = "source_type") String sourceType,
        @RequestParam(required = false) String status, @RequestParam(required = false, name = "agent_id") String agentId,
        @RequestParam(defaultValue = "false", name = "unread_only") boolean unreadOnly
    ) { return Map.of("events", inbox.listEvents(limit, offset, sourceType, status, agentId, unreadOnly)); }

    @PostMapping("/console/inbox/read")
    public Map<String, Object> read(@RequestBody(required = false) MarkReadRequest body) {
        boolean all = body != null && body.all();
        List<String> ids = body == null || body.eventIds() == null ? List.of() : body.eventIds();
        return Map.of("updated", all ? inbox.markAllRead() : inbox.markRead(ids));
    }

    @DeleteMapping("/console/inbox/events/{eventId}")
    public ResponseEntity<?> delete(@PathVariable String eventId) {
        InboxService.DeletedEvent result = inbox.deleteEvent(eventId);
        if (!result.deleted()) return ResponseEntity.status(404).body(Map.of("detail", "event not found"));
        boolean traceDeleted = result.runId() != null && !result.runIdStillReferenced() && inbox.deleteTrace(result.runId());
        return ResponseEntity.ok(Map.of("deleted", true, "trace_deleted", traceDeleted, "run_id", result.runId() == null ? "" : result.runId()));
    }

    @GetMapping("/console/inbox/traces/{runId}")
    public ResponseEntity<?> trace(@PathVariable String runId) {
        Map<String, Object> trace = inbox.trace(runId);
        return trace == null ? ResponseEntity.status(404).body(Map.of("detail", "trace not found")) : ResponseEntity.ok(trace);
    }

    @GetMapping("/agents/{agentId}/console/inbox/events")
    public Map<String, Object> agentList(@PathVariable String agentId, @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false, name = "source_type") String sourceType,
        @RequestParam(required = false) String status, @RequestParam(required = false, name = "agent_id") String requestedAgentId,
        @RequestParam(defaultValue = "false", name = "unread_only") boolean unreadOnly) {
        return list(limit, offset, sourceType, status, requestedAgentId == null ? agentId : requestedAgentId, unreadOnly);
    }
    @PostMapping("/agents/{agentId}/console/inbox/read")
    public Map<String, Object> agentRead(@PathVariable String agentId, @RequestBody(required = false) MarkReadRequest body) { return read(body); }
    @DeleteMapping("/agents/{agentId}/console/inbox/events/{eventId}")
    public ResponseEntity<?> agentDelete(@PathVariable String agentId, @PathVariable String eventId) { return delete(eventId); }
    @GetMapping("/agents/{agentId}/console/inbox/traces/{runId}")
    public ResponseEntity<?> agentTrace(@PathVariable String agentId, @PathVariable String runId) { return trace(runId); }

    public record MarkReadRequest(List<String> eventIds, boolean all) {
        @com.fasterxml.jackson.annotation.JsonProperty("event_ids") public List<String> eventIds() { return eventIds; }
    }
}
