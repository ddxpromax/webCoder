package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ConsoleController {
    private static final Logger log =
        LoggerFactory.getLogger(ConsoleController.class);
    private static final String SYSTEM_PROMPT =
        "You are a professional coding assistant. "
        + "Use available tools to inspect and modify code when asked. "
        + "Answer clearly and concisely.";

    private final HarnessAgentFactory agentFactory;
    private final ChatService chats;
    private final TaskTracker tasks;
    private final ActiveTurnRegistry turns;
    private final ObjectMapper mapper;
    private final String version;

    public ConsoleController(
        HarnessAgentFactory agentFactory,
        ChatService chats,
        TaskTracker tasks,
        ActiveTurnRegistry turns,
        ObjectMapper mapper,
        @Value("${majo.version:0.4.0}") String version
    ) {
        this.agentFactory = agentFactory;
        this.chats = chats;
        this.tasks = tasks;
        this.turns = turns;
        this.mapper = mapper;
        this.version = version;
    }

    @PostMapping(
        value = "/console/chat",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> consoleChat(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        String prompt = extractPrompt(body);
        QwenEvents events = new QwenEvents(mapper);
        if (prompt.isBlank()) {
            return Flux.just(events.error("prompt is required"));
        }
        String sessionId = text(
            body.get("session_id"), UUID.randomUUID().toString()
        );
        String agentId = text(
            request.getHeader("X-Agent-Id"),
            text(body.get("agent_id"), AgentStore.DEFAULT_AGENT_ID)
        );
        String workspaceInput = text(body.get("workspace"), "");
        Path workspace = resolveWorkspace(workspaceInput, agentId);
        ChatEntity chat = chats.getOrCreateBySession(
            agentId, sessionId, prompt
        );
        String chatId = chat.getId();
        chats.setStatus(chatId, "running");
        tasks.setRunning(chatId);

        HarnessAgent agent;
        try {
            agent = agentFactory.builder(
                agentId,
                SYSTEM_PROMPT,
                workspace,
                agentFactory.modelFor(
                    agentId,
                    HarnessAgentFactory.ModelFallback.SETTINGS
                )
            ).build();
            turns.register(chatId, agent);
        } catch (Exception exception) {
            tasks.setDone(chatId);
            chats.setStatus(chatId, "idle");
            log.warn("Failed to initialize agent for chat {}", chatId,
                exception);
            return Flux.just(events.error(
                "Agent initialization failed: "
                    + exception.getMessage()
            ));
        }

        RuntimeContext context = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(text(body.get("user_id"), "web-user"))
            .build();
        String responseId = "response_"
            + UUID.randomUUID().toString().replace("-", "");
        TurnState state = new TurnState();

        Flux<String> streamed = agent.streamEvents(
                new UserMessage(prompt), context
            )
            .concatMap(event -> {
                try {
                    return Flux.fromIterable(encodeAgentEvent(
                        event, state, events
                    ));
                } catch (Exception exception) {
                    log.debug("Agent event conversion failed", exception);
                    return Flux.empty();
                }
            });

        return Flux.concat(
            Flux.just(
                events.responseCreated(responseId, sessionId),
                events.responseInProgress(responseId, sessionId)
            ),
            streamed,
            Flux.defer(() -> Flux.just(
                events.responseCompleted(
                    responseId,
                    sessionId,
                    state.inputTokens,
                    state.outputTokens,
                    state.completedMessages
                ),
                events.turnUsage(
                    sessionId,
                    state.inputTokens,
                    state.outputTokens
                )
            ))
        ).onErrorResume(exception -> {
            log.warn("Agent turn failed for chat {}", chatId,
                exception);
            return Flux.just(events.error(
                Objects.toString(exception.getMessage(),
                    "Agent turn failed")
            ));
        }).doFinally(signal -> {
            try {
                saveConsoleMessages(
                    chatId, prompt, state.completedMessages
                );
                chats.setStatus(chatId, "idle");
                tasks.setDone(chatId);
            } catch (Exception exception) {
                log.error("Failed to persist chat {}", chatId,
                    exception);
            } finally {
                turns.unregister(chatId);
            }
        });
    }

    @PostMapping("/console/chat/stop")
    public Map<String, Object> stopChat(
        @RequestParam(
            value = "chat_id",
            required = false
        ) String chatId
    ) {
        boolean stopped = turns.stop(chatId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("stopped", stopped);
        return response;
    }

    @GetMapping("/loops/status")
    public Map<String, Object> loopStatus() {
        return tasks.getGlobalStatus();
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", version);
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    private Path resolveWorkspace(String input, String agentId) {
        if (!input.isBlank()) {
            Path explicit = Path.of(input).toAbsolutePath()
                .normalize();
            if (Files.isDirectory(explicit)) {
                return explicit;
            }
        }
        return agentFactory.workspaceFor(agentId);
    }

    private List<String> encodeAgentEvent(
        Object event,
        TurnState state,
        QwenEvents events
    ) {
        String type = event.getClass().getSimpleName();
        switch (type) {
            case "ThinkingBlockStartEvent":
                state.thinkingMessageId = newMessageId();
                state.thinking.setLength(0);
                return List.of(events.reasoningInProgress(
                    state.thinkingMessageId
                ));
            case "ThinkingBlockDeltaEvent":
                String thinkingDelta = eventDelta(event);
                state.thinking.append(thinkingDelta);
                return List.of(events.contentDelta(
                    state.thinkingMessageId,
                    thinkingDelta
                ));
            case "ThinkingBlockEndEvent":
                String reasoning = state.thinking.toString();
                if (!reasoning.isBlank()) {
                    state.completedMessages.add(completedMessage(
                        state.thinkingMessageId,
                        "reasoning",
                        reasoning
                    ));
                }
                return List.of(
                    events.contentFinal(
                        state.thinkingMessageId, reasoning
                    ),
                    events.reasoningCompleted(
                        state.thinkingMessageId, reasoning
                    )
                );
            case "TextBlockStartEvent":
                state.textMessageId = newMessageId();
                state.text.setLength(0);
                return List.of(events.textMessageInProgress(
                    state.textMessageId
                ));
            case "TextBlockDeltaEvent":
                String textDelta = eventDelta(event);
                state.text.append(textDelta);
                return List.of(events.contentDelta(
                    state.textMessageId,
                    textDelta
                ));
            case "TextBlockEndEvent":
                String fullText = state.text.toString();
                if (!fullText.isBlank()) {
                    state.completedMessages.add(completedMessage(
                        state.textMessageId,
                        "message",
                        fullText
                    ));
                }
                return List.of(
                    events.contentFinal(
                        state.textMessageId, fullText
                    ),
                    events.textMessageCompleted(
                        state.textMessageId, fullText
                    )
                );
            case "ModelCallEndEvent":
                var usage = mapper.valueToTree(event)
                    .path("usage");
                state.inputTokens = usage.path("inputTokens")
                    .asInt(state.inputTokens);
                state.outputTokens = usage.path("outputTokens")
                    .asInt(state.outputTokens);
                return List.of();
            default:
                return List.of();
        }
    }

    private static Map<String, Object> completedMessage(
        String id,
        String type,
        String text
    ) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", type);
        message.put("role", "assistant");
        message.put("content", List.of(Map.of(
            "type", "text", "text", text
        )));
        message.put("status", "completed");
        return message;
    }

    private void saveConsoleMessages(
        String chatId,
        String prompt,
        List<Map<String, Object>> completed
    ) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", mapper.writeValueAsString(
            List.of(Map.of("type", "text", "text", prompt))
        ));
        messages.add(user);
        for (Map<String, Object> event : completed) {
            Map<String, String> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", mapper.writeValueAsString(
                event.get("content")
            ));
            if ("reasoning".equals(event.get("type"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) event.get("content");
                assistant.put("thinking",
                    String.valueOf(parts.getFirst().get("text"))
                );
            }
            messages.add(assistant);
        }
        chats.saveMessages(chatId, messages);
    }

    private static String eventDelta(Object event) {
        try {
            Object delta = event.getClass().getMethod("getDelta")
                .invoke(event);
            return delta == null ? "" : String.valueOf(delta);
        } catch (ReflectiveOperationException exception) {
            return "";
        }
    }

    private static String extractPrompt(Map<String, Object> body) {
        Object input = body.get("input");
        if (input instanceof String value && !value.isBlank()) {
            return value;
        }
        if (input instanceof List<?> list && !list.isEmpty()
            && list.getFirst() instanceof Map<?, ?> message) {
            Object content = message.get("content");
            if (content instanceof String value) {
                return value;
            }
            if (content instanceof List<?> parts
                && !parts.isEmpty()
                && parts.getFirst() instanceof Map<?, ?> part) {
                return text(part.get("text"), "");
            }
        }
        return "";
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString()
            .replace("-", "");
    }

    private static String text(Object value, String fallback) {
        String result = value == null ? "" : String.valueOf(value);
        return result.isBlank() ? fallback : result;
    }

    private static class TurnState {
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder text = new StringBuilder();
        final List<Map<String, Object>> completedMessages =
            new ArrayList<>();
        String thinkingMessageId;
        String textMessageId;
        int inputTokens;
        int outputTokens;
    }
}
