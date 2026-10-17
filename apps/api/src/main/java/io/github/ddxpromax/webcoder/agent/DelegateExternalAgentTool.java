package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.ddxpromax.webcoder.acp.AcpPermissionBridge;

/**
 * Majo-compatible ACP delegation over a Content-Length framed JSON-RPC
 * stdio connection. ACP runners keep ownership of their tools; this tool
 * owns the runner lifecycle and exposes foreground results or a background
 * task ID through the ordinary subagent task registry.
 */
public class DelegateExternalAgentTool {
    private static final Logger log = LoggerFactory.getLogger(DelegateExternalAgentTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    private static final int MAX_RESULT_CHARS = 16_000;
    private static final ExecutorService BACKGROUND = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "majo-acp-delegate");
        thread.setDaemon(true);
        return thread;
    });

    private final String agentId;
    private final Path workspace;
    private final AgentStore agents;
    private final SubagentTaskRegistry tasks;
    private final AcpPermissionBridge permissions;

    public DelegateExternalAgentTool(String agentId, Path workspace, AgentStore agents, SubagentTaskRegistry tasks, AcpPermissionBridge permissions) {
        this.agentId = agentId;
        this.workspace = workspace;
        this.agents = agents;
        this.tasks = tasks;
        this.permissions = permissions;
    }

    @Tool(
        name = "delegate_external_agent",
        description = "Delegate work to an external ACP agent runner. Use background=true to return a task ID immediately."
    )
    public String delegateExternalAgent(
        @ToolParam(name = "task", description = "The task for the external agent") String task,
        @ToolParam(name = "agent_name", description = "Configured ACP agent name, default opencode", required = false) String agentName,
        @ToolParam(name = "background", description = "Run as a background task", required = false) Boolean background
    ) {
        if (task == null || task.isBlank()) return "task must not be empty";
        String name = agentName == null || agentName.isBlank() ? "opencode" : agentName.trim();
        if (entry(name) == null) return "ACP agent '" + name + "' is not configured or is disabled.";
        if (!Boolean.TRUE.equals(background)) return run(name, task, null);

        SubagentTaskRegistry.Task registered = tasks.register("acp:" + name);
        BACKGROUND.submit(() -> {
            try {
                String result = run(name, task, snapshot -> tasks.updateProgress(registered.taskId, snapshot));
                if ("running".equals(registered.status)) tasks.complete(registered.taskId, result);
            } catch (Exception exception) {
                log.warn("ACP delegation {} failed", registered.taskId, exception);
                tasks.fail(registered.taskId, message(exception));
            }
        });
        return "External ACP agent '" + name + "' started in the background.\n"
            + "task_id: " + registered.taskId + "\n"
            + "Use check_agent_task with this task ID to inspect progress and the result.";
    }

    private String run(String name, String task, Consumer<String> progress) {
        Map<String, Object> configured = entry(name);
        if (configured == null) return "ACP agent '" + name + "' is not configured or is disabled.";
        String command = text(configured.get("command"));
        if (command.isBlank()) return "ACP agent '" + name + "' has no command configured.";
        List<String> invocation = new ArrayList<>();
        invocation.add(command);
        invocation.addAll(strings(configured.get("args")));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(invocation).redirectErrorStream(true);
            builder.directory(workspace.toFile());
            environment(configured.get("env")).forEach(builder.environment()::put);
            process = builder.start();
            Process activeProcess = process;
            try (Writer output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                 InputStream input = process.getInputStream()) {
                send(output, 1, "initialize", Map.of("protocolVersion", 1, "clientCapabilities", Map.of()));
                JsonNode initialized = read(input, activeProcess, Duration.ofSeconds(15));
                if (initialized == null || initialized.has("error")) {
                    return "ACP agent '" + name + "' did not complete initialization.";
                }
                String result = session(output, input, activeProcess, task, progress);
                if (result == null) result = legacy(output, input, activeProcess, task, progress);
                if (result == null || result.isBlank()) return "ACP agent '" + name + "' returned no result.";
                return truncate(result);
            } finally {
                stop(process);
            }
        } catch (Exception exception) {
            log.warn("ACP runner '{}' failed: {}", name, message(exception));
            return "ACP delegation failed: " + message(exception);
        }
    }

    /** ACP session/new then session/prompt. A null result means legacy fallback. */
    private String session(Writer output, InputStream input, Process process, String task, Consumer<String> progress) throws Exception {
        send(output, 2, "session/new", Map.of("cwd", workspace.toString(), "mcpServers", List.of()));
        Collector collector = new Collector(progress);
        JsonNode created = await(output, input, process, 2, collector);
        if (created == null || created.has("error")) return null;
        String sessionId = created.path("result").path("sessionId").asText();
        if (sessionId.isBlank()) return null;
        send(output, 3, "session/prompt", Map.of(
            "sessionId", sessionId,
            "prompt", List.of(Map.of("type", "text", "text", task))
        ));
        JsonNode completed = await(output, input, process, 3, collector);
        if (completed == null) return "ACP agent timed out before completing the session.";
        if (completed.has("error")) return "ACP agent error: " + completed.path("error").path("message").asText("unknown error");
        String result = collector.text.toString().strip();
        if (result.isBlank()) result = extractText(completed.path("result"));
        String trace = collector.trace();
        return trace.isBlank() ? result : result + "\n\n" + trace;
    }

    /** Compatibility for older ACP runners that implement only agent/task. */
    private String legacy(Writer output, InputStream input, Process process, String task, Consumer<String> progress) throws Exception {
        send(output, 4, "agent/task", Map.of(
            "id", "task-" + UUID.randomUUID().toString().substring(0, 8),
            "prompt", task,
            "options", Map.of("maxSteps", 20)
        ));
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        StringBuilder text = new StringBuilder();
        while (System.nanoTime() < deadline) {
            JsonNode frame = read(input, process, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame == null) break;
            if (frame.has("method")) {
                replyRunnerRequest(output, frame);
                continue;
            }
            if (frame.path("id").asInt(-1) != 4) continue;
            JsonNode result = frame.path("result");
            String part = extractText(result);
            if (!part.isBlank()) text.append(part).append('\n');
            publish(progress, text.length(), 0, "");
            String status = result.path("status").asText();
            if ("completed".equals(status) || "cancelled".equals(status) || "failed".equals(status)) return text.toString().strip();
        }
        return text.isEmpty() ? null : text.toString().strip();
    }

    private JsonNode await(Writer output, InputStream input, Process process, int id, Collector collector) throws Exception {
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            JsonNode frame = read(input, process, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame == null) return null;
            if ("session/update".equals(frame.path("method").asText())) {
                collector.update(frame.path("params").path("update"));
            } else if (frame.has("method")) {
                replyRunnerRequest(output, frame);
            } else if (frame.path("id").asInt(-1) == id) {
                return frame;
            }
        }
        return null;
    }

    /** ACP runners can request permissions; no user session is available inside the child protocol, so cancel safely. */
    private void replyRunnerRequest(Writer output, JsonNode frame) throws Exception {
        if (!frame.hasNonNull("id")) return;
        int id = frame.path("id").asInt();
        String method = frame.path("method").asText();
        if (method.endsWith("request_permission")) {
            reply(output, id, permissions == null ? Map.of("outcome", Map.of("outcome", "cancelled")) : permissions.resolve(frame.path("params")));
        } else {
            reply(output, id, Map.of("error", Map.of("code", -32601, "message", "Method is not supported by the delegating client.")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entry(String name) {
        Map<String, Object> profile = agents.getProfile(agentId);
        Object rawAcp = profile == null ? null : profile.get("acp");
        Map<String, Object> acp = rawAcp instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        Object rawEntries = acp.get("agents");
        Map<String, Object> entries = rawEntries instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        defaults().forEach(entries::putIfAbsent);
        Object raw = entries.get(name);
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) map);
        return Boolean.FALSE.equals(result.get("enabled")) || "false".equalsIgnoreCase(text(result.get("enabled"))) ? null : result;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("opencode", defaultEntry("opencode", List.of("acp")));
        entries.put("qwen_code", defaultEntry("qwen", List.of("--acp")));
        entries.put("claude_code", defaultEntry("npx", List.of("-y", "@zed-industries/claude-agent-acp")));
        entries.put("codex", defaultEntry("npx", List.of("-y", "@zed-industries/codex-acp")));
        return entries;
    }

    private static Map<String, Object> defaultEntry(String command, List<String> args) {
        return Map.of("enabled", true, "command", command, "args", args, "env", Map.of());
    }

    private static Map<String, String> environment(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, value) -> { if (key != null && value != null) result.put(String.valueOf(key), String.valueOf(value)); });
        return result;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(value -> value != null).map(String::valueOf).toList();
    }

    private static void send(Writer output, int id, String method, Map<String, Object> params) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0"); frame.put("id", id); frame.put("method", method); frame.put("params", params);
        write(output, frame);
    }

    private static void reply(Writer output, int id, Map<String, Object> payload) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0"); frame.put("id", id); frame.putAll(payload);
        write(output, frame);
    }

    private static void write(Writer output, Map<String, Object> frame) throws IOException {
        byte[] body = JSON.writeValueAsBytes(frame);
        output.write("Content-Length: " + body.length + "\r\n\r\n");
        output.write(new String(body, StandardCharsets.UTF_8));
        output.flush();
    }

    /** Byte-oriented frame parsing: Content-Length is UTF-8 bytes, not Java characters. */
    static JsonNode readFrame(InputStream input) throws IOException {
        int contentLength = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int next = input.read();
            if (next < 0) return null;
            if (next == '\r') continue;
            if (next != '\n') { line.append((char) next); continue; }
            if (line.isEmpty()) break;
            String header = line.toString();
            if (header.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                try { contentLength = Integer.parseInt(header.substring("Content-Length:".length()).trim()); }
                catch (NumberFormatException ignored) { return null; }
            }
            line.setLength(0);
        }
        if (contentLength < 0 || contentLength > 50 * 1024 * 1024) return null;
        byte[] body = input.readNBytes(contentLength);
        return body.length == contentLength ? JSON.readTree(body) : null;
    }

    private static JsonNode read(InputStream input, Process process, Duration timeout) throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "majo-acp-frame-reader"); thread.setDaemon(true); return thread;
        });
        try {
            return reader.submit(() -> readFrame(input)).get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            process.destroyForcibly();
            return null;
        } finally {
            reader.shutdownNow();
        }
    }

    private static String extractText(JsonNode result) {
        if (result == null || result.isMissingNode()) return "";
        StringBuilder output = new StringBuilder();
        if (result.path("message").isTextual()) output.append(result.path("message").asText());
        collectText(result.path("result"), output);
        if (output.isEmpty() && result.path("text").isTextual()) output.append(result.path("text").asText());
        return output.toString().strip();
    }

    private static void collectText(JsonNode node, StringBuilder output) {
        if (node == null || node.isMissingNode()) return;
        if (node.isArray()) { for (JsonNode item : node) collectText(item, output); return; }
        if (node.isObject() && node.path("text").isTextual()) output.append(node.path("text").asText());
    }

    private static void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    }

    private static String truncate(String value) { return value.length() <= MAX_RESULT_CHARS ? value : value.substring(0, MAX_RESULT_CHARS) + "\n...[result truncated]"; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String message(Exception exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private static void publish(Consumer<String> sink, int chars, int calls, String latest) { if (sink != null) sink.accept("Received " + chars + " characters and " + calls + " tool calls" + (latest.isBlank() ? "." : "; latest: " + latest)); }

    private static final class Collector {
        private final StringBuilder text = new StringBuilder();
        private final LinkedHashMap<String, String> calls = new LinkedHashMap<>();
        private final Consumer<String> progress;
        private Collector(Consumer<String> progress) { this.progress = progress; }
        private void update(JsonNode update) {
            String kind = update.path("sessionUpdate").asText();
            if ("agent_message_chunk".equals(kind)) collectText(update.path("content"), text);
            if ("tool_call".equals(kind) || "tool_call_update".equals(kind)) {
                String id = update.path("toolCallId").asText();
                if (!id.isBlank()) calls.put(id, toolLabel(update));
            }
            String latest = calls.isEmpty() ? "" : calls.values().stream().reduce((first, second) -> second).orElse("");
            publish(progress, text.length(), calls.size(), latest);
        }
        private String trace() {
            if (calls.isEmpty()) return "";
            StringBuilder result = new StringBuilder("External agent tool calls:\n");
            calls.values().stream().limit(12).forEach(value -> result.append("- ").append(value).append('\n'));
            return result.toString().strip();
        }
        private static String toolLabel(JsonNode update) {
            String title = update.path("title").asText();
            if (title.isBlank()) title = update.path("rawInput").toString();
            String status = update.path("status").asText();
            return status.isBlank() ? title : title + " [" + status + "]";
        }
    }
}
