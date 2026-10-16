package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.inbox.InboxService;
import io.github.ddxpromax.webcoder.settings.SettingsService;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** Majo heartbeat reads HEARTBEAT.md, supports intervals or five-field cron, and reports through Inbox. */
@Component
public class HeartbeatScheduler {
    private final SettingsService settings; private final ChatService chats; private final InboxService inbox; private final HarnessAgentFactory agents;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler(); private ScheduledFuture<?> future; private volatile Map<String, Object> lastRun = Map.of("status", "never");
    public HeartbeatScheduler(SettingsService settings, ChatService chats, InboxService inbox, HarnessAgentFactory agents) { this.settings = settings; this.chats = chats; this.inbox = inbox; this.agents = agents; scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("majo-heartbeat-"); scheduler.initialize(); }
    public synchronized void reschedule() { if (future != null) future.cancel(false); future = null; Map<String,Object> config = settings.getHeartbeat(); if (!Boolean.TRUE.equals(config.get("enabled"))) return; String every = String.valueOf(config.get("every")); try { future = every.contains(" ") ? scheduler.schedule(this::runSafe, new CronTrigger("0 " + every)) : scheduler.scheduleAtFixedRate(this::runSafe, interval(every)); } catch (Exception ignored) { future = scheduler.scheduleAtFixedRate(this::runSafe, Duration.ofHours(6)); } }
    public Map<String,Object> runNow() { boolean enabled = Boolean.TRUE.equals(settings.getHeartbeat().get("enabled")); if (enabled) scheduler.execute(this::runSafe); return Map.of("started", enabled, "last_run", lastRun); }
    public Map<String,Object> lastRun() { return lastRun; }
    @EventListener(ApplicationReadyEvent.class) public void ready() { reschedule(); }
    private void runSafe() { Instant started = Instant.now(); try { String agentId = "default"; var workspace = agents.workspaceFor(agentId); var file = workspace.resolve("HEARTBEAT.md"); if (!Files.isRegularFile(file)) { lastRun = Map.of("status", "skipped", "started_at", started.toString(), "detail", "HEARTBEAT.md missing"); return; } String prompt = Files.readString(file).strip(); if (prompt.isEmpty()) { lastRun = Map.of("status", "skipped", "started_at", started.toString(), "detail", "HEARTBEAT.md empty"); return; } int timeout = ((Number) settings.getHeartbeat().get("timeoutSeconds")).intValue(); HarnessAgent agent = agents.builder(agentId, "You are a periodic coding assistant. Follow HEARTBEAT.md and return a concise result.", workspace, agents.modelFor(agentId, HarnessAgentFactory.ModelFallback.NONE)).build(); try { agent.streamEvents(new UserMessage(prompt), RuntimeContext.builder().sessionId("heartbeat-main").userId("heartbeat").build()).blockLast(Duration.ofSeconds(timeout)); lastRun = Map.of("status", "success", "started_at", started.toString(), "detail", "completed"); if (!"main".equals(settings.getHeartbeat().get("target"))) inbox.appendEvent(agentId, "heartbeat", "", "heartbeat_result", "success", "Heartbeat completed", "Heartbeat completed successfully.", "info", Map.of("target", settings.getHeartbeat().get("target"), "status", "success")); } finally { agents.close(agent); } } catch (Exception exception) { String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); lastRun = Map.of("status", "error", "started_at", started.toString(), "detail", detail); inbox.appendEvent("default", "heartbeat", "", "heartbeat_result", "error", "Heartbeat failed", detail, "error", Map.of("target", settings.getHeartbeat().get("target"), "status", "error")); } }
    private Duration interval(String value) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([smh])$").matcher(value.strip()); if (!m.matches()) throw new IllegalArgumentException(); long n = Long.parseLong(m.group(1)); return switch (m.group(2)) { case "s" -> Duration.ofSeconds(n); case "m" -> Duration.ofMinutes(n); default -> Duration.ofHours(n); }; }
}
