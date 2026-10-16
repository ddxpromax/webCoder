package io.github.ddxpromax.webcoder.inbox;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxService {
    private final InboxRepository events;
    private final InboxTraceRepository traces;

    public InboxService(InboxRepository events, InboxTraceRepository traces) {
        this.events = events;
        this.traces = traces;
    }

    @Transactional
    public Map<String, Object> appendEvent(
        String agentId, String sourceType, String sourceId, String eventType,
        String status, String title, String body, String severity, Map<String, Object> payload
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        InboxEvent event = events.insert(new InboxEvent(UUID.randomUUID().toString(), value(agentId, "default"),
            value(sourceType, ""), value(sourceId, ""), value(eventType, ""), value(status, ""),
            value(severity, "info"), value(title, ""), value(body, ""),
            payload == null ? Map.of() : new LinkedHashMap<>(payload), false, now, now));
        return toMap(event);
    }

    public List<Map<String, Object>> listEvents(int limit, int offset, String sourceType, String status, String agentId, boolean unreadOnly) {
        return events.list(new InboxRepository.InboxQuery(limit, offset, sourceType, status, agentId, unreadOnly))
            .stream().map(InboxService::toMap).toList();
    }

    @Transactional
    public int markRead(List<String> ids) { return events.markRead(ids); }

    @Transactional
    public int markAllRead() { return events.markAllRead(); }

    @Transactional
    public DeletedEvent deleteEvent(String eventId) {
        InboxEvent event = events.find(eventId).orElse(null);
        if (event == null) return new DeletedEvent(false, null, false);
        String runId = string(event.payload().get("run_id"));
        events.delete(eventId);
        return new DeletedEvent(true, runId, runId != null && events.referencesRun(runId));
    }

    @Transactional
    public void createTrace(String runId, String agentId, Map<String, Object> meta) { traces.create(runId, agentId, meta); }
    @Transactional
    public void appendTrace(String runId, List<Map<String, Object>> traceEvents) { traces.append(runId, traceEvents); }
    @Transactional
    public void finalizeTrace(String runId, String status, String error) { traces.finalizeTrace(runId, status, error); }
    public Map<String, Object> trace(String runId) {
        return traces.find(runId).map(InboxService::traceMap).orElse(null);
    }
    @Transactional
    public boolean deleteTrace(String runId) { return traces.delete(runId); }

    public static Map<String, Object> toMap(InboxEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", event.id()); item.put("agent_id", event.agentId());
        item.put("source_type", event.sourceType()); item.put("source_id", event.sourceId());
        item.put("event_type", event.eventType()); item.put("status", event.status());
        item.put("severity", event.severity()); item.put("title", event.title()); item.put("body", event.body());
        item.put("payload", event.payload()); item.put("read", event.read());
        item.put("created_at", epoch(event.createdAt())); item.put("updated_at", epoch(event.updatedAt()));
        return item;
    }

    private static Map<String, Object> traceMap(InboxTraceRepository.Trace trace) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("run_id", trace.runId()); item.put("agent_id", trace.agentId()); item.put("status", trace.status());
        item.put("meta", trace.meta()); item.put("events", trace.events()); item.put("error", trace.error());
        item.put("created_at", epoch(trace.createdAt())); item.put("completed_at", epoch(trace.completedAt()));
        return item;
    }

    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String string(Object value) { return value instanceof String s && !s.isBlank() ? s : null; }
    private static Double epoch(LocalDateTime time) { return time == null ? null : time.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000.0; }

    public record DeletedEvent(boolean deleted, String runId, boolean runIdStillReferenced) {}
}
