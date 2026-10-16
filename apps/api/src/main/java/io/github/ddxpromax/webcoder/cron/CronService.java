package io.github.ddxpromax.webcoder.cron;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.agent.HarnessAgentFactory;
import io.github.ddxpromax.webcoder.agent.StreamTextCollector;
import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.inbox.InboxService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, multi-instance-safe cron runner with Majo-compatible state/history responses. */
@Service
public class CronService {
    private static final String SYSTEM_PROMPT = "You are a professional coding assistant running a scheduled task. Use available tools when needed and return a concise result.";
    private final CronRepository jobs;
    private final InboxService inbox;
    private final ChatService chats;
    private final HarnessAgentFactory agents;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread thread = new Thread(r, "majo-cron"); thread.setDaemon(true); return thread; });

    public CronService(CronRepository jobs, InboxService inbox, ChatService chats, HarnessAgentFactory agents) { this.jobs = jobs; this.inbox = inbox; this.chats = chats; this.agents = agents; }

    public List<Map<String, Object>> list(String agentId) { return jobs.list(agent(agentId)).stream().map(this::view).toList(); }
    public Map<String, Object> get(String agentId, String jobId) {
        return jobs.find(agent(agentId), jobId).map(job -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("spec", job.spec()); result.put("state", state(job));
            return result;
        }).orElse(null);
    }
    public Map<String, Object> state(String agentId, String jobId) { return jobs.find(agent(agentId), jobId).map(this::state).orElse(null); }
    public List<Map<String, Object>> history(String agentId, String jobId) { return jobs.history(agent(agentId), jobId); }

    @Transactional
    public Map<String, Object> create(String agentId, Map<String, Object> request) {
        Map<String, Object> spec = CronModels.copy(request); CronModels.validate(spec); String id = UUID.randomUUID().toString(); spec.put("id", id);
        jobs.upsert(agent(agentId), id, spec, next(spec, Instant.now(), 0)); return spec;
    }
    @Transactional
    public Map<String, Object> replace(String agentId, String jobId, Map<String, Object> request) {
        Map<String, Object> spec = CronModels.copy(request); Object given = spec.get("id"); if (given != null && !jobId.equals(String.valueOf(given))) throw new MismatchException();
        CronModels.validate(spec); spec.put("id", jobId); jobs.upsert(agent(agentId), jobId, spec, next(spec, Instant.now(), history(agentId, jobId).size())); return spec;
    }
    @Transactional public boolean delete(String agentId, String jobId) { return jobs.delete(agent(agentId), jobId); }
    @Transactional public boolean pause(String agentId, String jobId) { return jobs.pause(agent(agentId), jobId); }
    @Transactional public boolean resume(String agentId, String jobId) { return jobs.find(agent(agentId), jobId).map(job -> jobs.resume(agent(agentId), jobId, next(job.spec(), Instant.now(), history(agentId, jobId).size()))).orElse(false); }

    public boolean run(String agentId, String jobId) {
        CronRepository.Job job = jobs.find(agent(agentId), jobId).orElse(null); if (job == null) return false;
        workers.execute(() -> execute(job, "manual")); return true;
    }

    /** Polling plus atomic conditional claim makes deployments with several API replicas safe. */
    @Scheduled(fixedDelayString = "${majo.cron.poll-ms:15000}")
    public void dispatchDue() {
        for (CronRepository.Job job : jobs.due(64)) {
            int max = Math.max(1, CronModels.integer(CronModels.runtime(job.spec()).get("max_concurrency"), 1));
            LocalDateTime next = next(job.spec(), Instant.now(), history(job.agentId(), job.id()).size());
            if (jobs.claim(job.id(), max, next)) workers.execute(() -> executeClaimed(job, "scheduled"));
        }
    }

    private void execute(CronRepository.Job job, String trigger) {
        int max = Math.max(1, CronModels.integer(CronModels.runtime(job.spec()).get("max_concurrency"), 1));
        if (!jobs.claim(job.id(), max, next(job.spec(), Instant.now(), history(job.agentId(), job.id()).size()))) return;
        executeClaimed(job, trigger);
    }

    private void executeClaimed(CronRepository.Job job, String trigger) {
        String runId = UUID.randomUUID().toString(); LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC); String status = "success"; String error = null; String finalText = null;
        try { finalText = executeTask(job, runId); }
        catch (Exception exception) { status = "error"; error = message(exception); }
        LocalDateTime finished = LocalDateTime.now(ZoneOffset.UTC);
        jobs.finish(job.id(), status, error);
        jobs.addRun(new CronRepository.Run(runId, job.id(), job.agentId(), status, started, finished, error, Map.of("trigger", trigger, "run_id", runId)));
        appendInbox(job, runId, trigger, status, error, finalText);
    }

    private String executeTask(CronRepository.Job job, String runId) {
        Map<String, Object> spec = job.spec(); Map<String, Object> dispatch = CronModels.dispatch(spec); Map<String, Object> target = CronModels.map(dispatch.get("target"));
        String sessionId = CronModels.string(target.get("session_id"), ""); String userId = CronModels.string(target.get("user_id"), "cron");
        if ("text".equals(CronModels.string(spec.get("task_type"), "agent"))) {
            String text = CronModels.string(spec.get("text"), ""); deliver(job.agentId(), sessionId.isBlank() ? "cron" : sessionId, text); return text;
        }
        Map<String, Object> request = CronModels.map(spec.get("request")); String prompt = extractPrompt(request); if (prompt.isBlank()) throw new IllegalArgumentException("request.input is empty");
        inbox.createTrace(runId, job.agentId(), Map.of("job_id", job.id(), "job_name", CronModels.string(spec.get("name"), ""), "task_type", "agent", "dispatch_channel", CronModels.string(dispatch.get("channel"), "console"), "target_user_id", userId, "target_session_id", sessionId));
        HarnessAgent harness = null;
        try {
            String effectiveSession = sessionId.isBlank() ? "cron:" + job.id() : sessionId;
            ChatEntity chat = chats.getOrCreateBySession(job.agentId(), effectiveSession, CronModels.string(spec.get("name"), "Scheduled task"));
            var model = agents.modelFor(job.agentId(), HarnessAgentFactory.ModelFallback.NONE); if (model == null) throw new IllegalStateException("No active model configured for scheduled task");
            harness = agents.builder(job.agentId(), SYSTEM_PROMPT, agents.workspaceFor(job.agentId()), model).build(); StreamTextCollector collected = new StreamTextCollector();
            harness.streamEvents(new UserMessage(prompt), RuntimeContext.builder().sessionId(effectiveSession).userId(userId).build()).doOnNext(collected::accept).blockLast(java.time.Duration.ofSeconds(Math.max(1, CronModels.integer(CronModels.runtime(spec).get("timeout_seconds"), 120))));
            String result = collected.text().trim(); if (result.isBlank()) result = "Agent cron task finished successfully."; deliver(job.agentId(), effectiveSession, result); inbox.appendTrace(runId, List.of(Map.of("event", "completed"))); inbox.finalizeTrace(runId, "success", null); return result;
        } catch (RuntimeException exception) { inbox.finalizeTrace(runId, "error", message(exception)); throw exception; }
        finally { if (harness != null) agents.close(harness); }
    }

    private void appendInbox(CronRepository.Job job, String runId, String trigger, String status, String error, String finalText) {
        if ("success".equals(status) && !CronModels.bool(job.spec().get("save_result_to_inbox"), true)) return;
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("job_id", job.id()); payload.put("job_name", CronModels.string(job.spec().get("name"), "")); payload.put("task_type", job.spec().get("task_type")); payload.put("trigger", trigger); payload.put("run_id", runId); if (error != null) payload.put("error", error);
        inbox.appendEvent(job.agentId(), "cron", job.id(), "success".equals(status) ? "cron_result" : "cron_failed", status, "success".equals(status) ? "Cron result: " + job.spec().get("name") : "Cron task failed: " + job.spec().get("name"), "success".equals(status) ? (finalText == null ? "Agent cron task finished successfully." : finalText) : error, "success".equals(status) ? "info" : "error", payload);
    }

    private void deliver(String agentId, String sessionId, String text) { ChatEntity chat = chats.getOrCreateBySession(agentId, sessionId, text); chats.saveMessages(chat.getId(), List.of(Map.of("role", "assistant", "content", "[{\"type\":\"text\",\"text\":" + jsonText(text) + "}]"))); }
    private String jsonText(String text) { try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text); } catch (Exception e) { return "\"\""; } }
    private LocalDateTime next(Map<String, Object> spec, Instant after, int completedRuns) {
        Map<String, Object> schedule = CronModels.schedule(spec); if ("cron".equals(CronModels.string(schedule.get("type"), "cron"))) { try { Instant next = CronExpression.parse("0 " + CronModels.string(schedule.get("cron"), "0 9 * * *")).next(after); return next == null ? null : LocalDateTime.ofInstant(next, ZoneOffset.UTC); } catch (Exception ignored) { return null; } }
        Instant first = CronModels.parseInstant(CronModels.string(schedule.get("run_at"), "")); if (first == null) return null; int days = CronModels.integer(schedule.get("repeat_every_days"), 0); if (days <= 0) return first.isAfter(after) ? LocalDateTime.ofInstant(first, ZoneOffset.UTC) : null;
        Instant candidate = first; while (!candidate.isAfter(after)) candidate = candidate.plusSeconds(days * 86400L); String end = CronModels.string(schedule.get("repeat_end_type"), "never"); if ("until".equals(end) && candidate.isAfter(CronModels.parseInstant(CronModels.string(schedule.get("repeat_until"), "")))) return null; if ("count".equals(end) && completedRuns >= CronModels.integer(schedule.get("repeat_count"), 1)) return null; return LocalDateTime.ofInstant(candidate, ZoneOffset.UTC);
    }
    private Map<String, Object> view(CronRepository.Job job) { return job.spec(); }
    private Map<String, Object> state(CronRepository.Job job) { Map<String, Object> state = new LinkedHashMap<>(); state.put("next_run_at", job.nextRunAt() == null ? null : job.nextRunAt().atOffset(ZoneOffset.UTC).toString()); state.put("last_run_at", job.lastStartedAt() == null ? null : job.lastStartedAt().atOffset(ZoneOffset.UTC).toString()); state.put("last_status", job.lastStatus()); state.put("last_error", job.lastError()); state.put("running_count", job.runningCount()); return state; }
    private String agent(String value) { return value == null || value.isBlank() ? "default" : value; }
    private String extractPrompt(Map<String, Object> request) { Object input = request.get("input"); if (input instanceof String text) return text; if (input instanceof List<?> parts && !parts.isEmpty() && parts.getFirst() instanceof Map<?, ?> first) { Object content = first.get("content"); return content instanceof String text ? text : ""; } return ""; }
    private String message(Exception exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    public static class MismatchException extends IllegalArgumentException { public MismatchException() { super("job_id mismatch"); } }
}
