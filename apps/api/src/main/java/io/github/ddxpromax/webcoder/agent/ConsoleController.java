package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.github.ddxpromax.webcoder.chat.ChatEntity;
import io.github.ddxpromax.webcoder.chat.ChatService;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.loop.CompletionGate;
import io.github.ddxpromax.webcoder.loop.DoomLoopGate;
import io.github.ddxpromax.webcoder.loop.IterationGate;
import io.github.ddxpromax.webcoder.loop.LoopCompiler;
import io.github.ddxpromax.webcoder.loop.LoopContext;
import io.github.ddxpromax.webcoder.loop.LoopGate;
import io.github.ddxpromax.webcoder.loop.LoopSessionManager;
import io.github.ddxpromax.webcoder.loop.StopHandler;
import io.github.ddxpromax.webcoder.loop.StopHandlerResult;
import io.github.ddxpromax.webcoder.loop.TimeoutGate;
import io.github.ddxpromax.webcoder.loop.TokenBudgetGate;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    private final ApprovalStore approvals;
    private final MemoryCommandService memoryCommands;
    private final TokenUsageService tokenUsage;
    private final ModelRoutingService modelRouting;
    private final AgentStore agents;
    private final LoopSessionManager loops;
    private final ObjectMapper mapper;
    private final MajoWorkingDirectory workingDirectory;
    private final String version;

    public ConsoleController(
        HarnessAgentFactory agentFactory,
        ChatService chats,
        TaskTracker tasks,
        ActiveTurnRegistry turns,
        ApprovalStore approvals,
        MemoryCommandService memoryCommands,
        TokenUsageService tokenUsage,
        ModelRoutingService modelRouting,
        AgentStore agents,
        LoopSessionManager loops,
        ObjectMapper mapper,
        MajoWorkingDirectory workingDirectory,
        @Value("${majo.version:0.4.0}") String version
    ) {
        this.agentFactory = agentFactory;
        this.chats = chats;
        this.tasks = tasks;
        this.turns = turns;
        this.approvals = approvals;
        this.memoryCommands = memoryCommands;
        this.tokenUsage = tokenUsage;
        this.modelRouting = modelRouting;
        this.agents = agents;
        this.loops = loops;
        this.mapper = mapper;
        this.workingDirectory = workingDirectory;
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
        List<Map<String, Object>> userContent = extractUserContent(body, prompt);
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
        String trimmedPrompt = prompt.stripLeading();
        if (trimmedPrompt.equalsIgnoreCase("/memory")
            || trimmedPrompt.toLowerCase().startsWith("/memory ")) {
            return consoleMemoryCommand(
                prompt, sessionId, agentId
            );
        }
        LoopCommand loopCommand = parseLoopCommand(prompt, agentId);
        if (loopCommand != null) {
            return consoleLoop(loopCommand, body, sessionId, agentId,
                text(body.get("workspace"), ""), prompt, userContent);
        }
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
        long turnStartMs = System.currentTimeMillis();
        var modelSlot = modelRouting.resolveEffectiveModel(agentId);

        UserMessage userMessage = buildUserMessage(userContent);
        Flux<String> streamed = OverflowRecovery.recover(
                agent.streamEvents(userMessage, context), agent,
                userMessage, context
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
                approvals.cancelSession(sessionId);
                saveConsoleMessages(chatId, userContent, state.completedMessages, state.completedToolCalls);
                chats.setStatus(chatId, "idle");
                tasks.setDone(chatId);
            } catch (Exception exception) {
                log.error("Failed to persist chat {}", chatId,
                    exception);
            } finally {
                turns.unregister(chatId);
                try {
                    agentFactory.close(agent);
                } catch (Exception exception) {
                    log.warn("Agent cleanup failed for chat {}",
                        chatId, exception);
                }
            }
            if (state.inputTokens > 0 || state.outputTokens > 0) {
                try {
                    tokenUsage.record(
                        agentId, chatId,
                        modelSlot == null ? "" : modelSlot.providerId(),
                        modelSlot == null ? "" : modelSlot.modelId(),
                        state.inputTokens,
                        state.outputTokens,
                        System.currentTimeMillis() - turnStartMs
                    );
                } catch (Exception exception) {
                    log.warn("Failed to record token usage for chat {}",
                        chatId, exception);
                }
            }
        });
    }

    private Flux<String> consoleLoop(
        LoopCommand command, Map<String, Object> body, String sessionId,
        String agentId, String workspaceInput, String originalPrompt,
        List<Map<String, Object>> userContent
    ) {
        QwenEvents events = new QwenEvents(mapper);
        Path workspace = resolveWorkspace(workspaceInput, agentId);
        ChatEntity chat = chats.getOrCreateBySession(agentId, sessionId, originalPrompt);
        String chatId = chat.getId(); chats.setStatus(chatId, "running"); tasks.setRunning(chatId);
        HarnessAgent agent;
        try {
            agent = agentFactory.builder(agentId, SYSTEM_PROMPT, workspace,
                agentFactory.modelFor(agentId, HarnessAgentFactory.ModelFallback.SETTINGS)).build();
            turns.register(chatId, agent);
        } catch (Exception exception) {
            tasks.setDone(chatId); chats.setStatus(chatId, "idle");
            return Flux.just(events.error("Agent initialization failed: " + exception.getMessage()));
        }
        RuntimeContext context = RuntimeContext.builder().sessionId(sessionId)
            .userId(text(body.get("user_id"), "web-user")).build();
        StopHandler handler;
        try {
            handler = buildLoopHandler(command.modeId, agentId);
        } catch (IllegalArgumentException exception) {
            tasks.setDone(chatId);
            chats.setStatus(chatId, "idle");
            try { agentFactory.close(agent); } catch (Exception ignored) { }
            return Flux.just(events.error(exception.getMessage()));
        }
        LoopSessionManager.LoopSession session = loops.start(
            sessionId, command.modeId, command.displayName, command.goal,
            handler
        );
        LoopAggregate aggregate = new LoopAggregate();
        String responseId = "response_" + UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.currentTimeMillis();
        var modelSlot = modelRouting.resolveEffectiveModel(agentId);
        return Flux.concat(
            Flux.just(events.responseCreated(responseId, sessionId), events.responseInProgress(responseId, sessionId)),
            streamLoopTurn(agent, buildUserMessage(command.goal.isBlank()
                ? userContent : withPrompt(userContent, command.goal)),
                context, session, aggregate, events),
            Flux.defer(() -> Flux.just(events.responseCompleted(responseId, sessionId, aggregate.inputTokens, aggregate.outputTokens, aggregate.messages), events.turnUsage(sessionId, aggregate.inputTokens, aggregate.outputTokens)))
        ).onErrorResume(exception -> Flux.just(events.error(Objects.toString(exception.getMessage(), "Agent loop failed"))))
        .doFinally(signal -> {
            try { approvals.cancelSession(sessionId); saveConsoleMessages(chatId, userContent, aggregate.messages, aggregate.toolCalls); chats.setStatus(chatId, "idle"); tasks.setDone(chatId); }
            catch (Exception exception) { log.error("Failed to persist loop chat {}", chatId, exception); }
            finally { loops.end(sessionId); turns.unregister(chatId); try { agentFactory.close(agent); } catch (Exception exception) { log.warn("Failed to close loop agent", exception); } }
            if (aggregate.inputTokens > 0 || aggregate.outputTokens > 0) tokenUsage.record(agentId, chatId, modelSlot == null ? "" : modelSlot.providerId(), modelSlot == null ? "" : modelSlot.modelId(), aggregate.inputTokens, aggregate.outputTokens, System.currentTimeMillis() - startedAt);
        });
    }

    private Flux<String> streamLoopTurn(HarnessAgent agent, UserMessage message, RuntimeContext context,
                                         LoopSessionManager.LoopSession session, LoopAggregate aggregate, QwenEvents events) {
        session.iteration++;
        TurnState turn = new TurnState();
        return OverflowRecovery.recover(agent.streamEvents(message, context), agent, message, context)
            .concatMap(event -> Flux.fromIterable(encodeAgentEvent(event, turn, events)))
            .concatWith(Flux.defer(() -> {
                aggregate.messages.addAll(turn.completedMessages); aggregate.toolCalls.addAll(turn.completedToolCalls); aggregate.inputTokens += turn.inputTokens; aggregate.outputTokens += turn.outputTokens;
                LoopGate.setCurrentSession(session.sessionId);
                StopHandlerResult result;
                try {
                    result = session.handler.run(new LoopContext(
                        session.iteration, turn.hasToolCalls,
                        turn.text.toString(), turn.lastToolCall,
                        turn.inputTokens + turn.outputTokens
                    ));
                } finally {
                    LoopGate.clearCurrentSession();
                }
                session.lastResult = result.reason;
                return result.isContinue() && loops.isActive(session.sessionId)
                    ? streamLoopTurn(agent, new UserMessage(result.continuationMessage), context, session, aggregate, events)
                    : Flux.empty();
            }));
    }

    private LoopCommand parseLoopCommand(String prompt, String agentId) {
        String trimmed = prompt == null ? "" : prompt.stripLeading();
        if (trimmed.startsWith("/goal")) return builtinLoop("goal", trimmed.substring(5).trim());
        if (trimmed.startsWith("/mission")) return builtinLoop("mission", trimmed.substring(8).trim());
        if (!trimmed.startsWith("/")) return null;
        int split = trimmed.indexOf(' '); String slash = split < 0 ? trimmed.substring(1) : trimmed.substring(1, split);
        for (Map<String, Object> mode : customModes(agentId)) {
            if (slash.equalsIgnoreCase(text(mode.get("slash_command"), ""))) {
                return new LoopCommand("custom:" + text(mode.get("id"), slash),
                    slash, split < 0 ? "" : trimmed.substring(split + 1).trim());
            }
        }
        return null;
    }

    private LoopCommand builtinLoop(String mode, String goal) {
        return new LoopCommand(mode, mode, goal);
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> customModes(String agentId) {
        Object rawLoop = agents.getRunningConfig(agentId).get("loop"); if (!(rawLoop instanceof Map<?, ?> loop)) return List.of();
        Object rawModes = loop.get("custom_modes"); if (!(rawModes instanceof List<?> modes)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(); for (Object mode : modes) if (mode instanceof Map<?, ?> item) result.add(new LinkedHashMap<>((Map<String, Object>) item)); return result;
    }
    private StopHandler buildLoopHandler(String modeId, String agentId) {
        if (modeId.startsWith("custom:")) {
            String id = modeId.substring("custom:".length());
            for (Map<String, Object> mode : customModes(agentId)) {
                if (id.equals(text(mode.get("id"), ""))) {
                    return LoopCompiler.defaultCompiler().compile(mode);
                }
            }
            throw new IllegalArgumentException("Custom loop mode not found: " + id);
        }
        StopHandler handler = new StopHandler();
        handler.register(DoomLoopGate.defaultConfig());
        handler.register(new IterationGate(20));
        if ("mission".equals(modeId)) {
            handler.register(new TimeoutGate(1800));
        } else {
            handler.register(new TokenBudgetGate(300_000));
            handler.register(new CompletionGate(null, 10));
        }
        return handler;
    }
    private record LoopCommand(String modeId, String displayName, String goal) { }
    private static final class LoopAggregate { int inputTokens; int outputTokens; final List<Map<String, Object>> messages = new ArrayList<>(); final List<Map<String, Object>> toolCalls = new ArrayList<>(); }

    @PostMapping("/console/chat/stop")
    public Map<String, Object> stopChat(
        @RequestParam(
            value = "chat_id",
            required = false
        ) String chatId
    ) {
        if (chatId != null && !chatId.isBlank()) {
            ChatEntity chat = chats.getChat(chatId);
            if (chat != null && chat.getSessionId() != null) {
                approvals.cancelSession(chat.getSessionId());
            }
        }
        boolean stopped = turns.stop(chatId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("stopped", stopped);
        return response;
    }

    @GetMapping("/loops/status")
    public Map<String, Object> loopStatus(
        @RequestParam(value = "chat_id", required = false) String chatId,
        @RequestParam(value = "session_id", required = false) String sessionId
    ) {
        String resolved = sessionId;
        if (chatId != null && !chatId.isBlank()) {
            ChatEntity chat = chats.getChat(chatId);
            if (chat != null && chat.getSessionId() != null && !chat.getSessionId().isBlank()) {
                resolved = chat.getSessionId();
            }
        }
        if (resolved != null && !resolved.isBlank()) {
            Map<String, Object> status = loops.status(resolved);
            if (!"idle".equals(status.get("state")) || chatId == null || chatId.isBlank()) return status;
        }
        if (chatId != null && "running".equals(tasks.getStatus(chatId))) {
            Map<String, Object> running = new LinkedHashMap<>(); running.put("state", "running"); running.put("mode", null); return running;
        }
        Map<String, Object> idle = new LinkedHashMap<>(); idle.put("state", "idle"); idle.put("mode", null); return idle;
    }

    @GetMapping("/console/push-messages")
    public Map<String, Object> pushMessages() {
        List<Map<String, Object>> pending = approvals.listPending()
            .stream().map(ApprovalController::toResponse).toList();
        return Map.of(
            "messages", List.of(),
            "pending_approvals", pending
        );
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", version);
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    private Flux<String> consoleMemoryCommand(
        String prompt,
        String sessionId,
        String agentId
    ) {
        String args = prompt.stripLeading()
            .substring("/memory".length());
        String reply;
        try {
            reply = memoryCommands.execute(agentId, args);
        } catch (Exception exception) {
            reply = "Memory command failed: "
                + exception.getMessage();
        }
        ChatEntity chat = chats.getOrCreateBySession(
            agentId, sessionId, prompt
        );
        String chatId = chat.getId();
        chats.setStatus(chatId, "running");
        tasks.setRunning(chatId);
        QwenEvents events = new QwenEvents(mapper);
        String responseId = "response_" + UUID.randomUUID()
            .toString().replace("-", "");
        String messageId = newMessageId();
        List<Map<String, Object>> completed = List.of(
            completedMessage(messageId, "message", reply)
        );
        String finalReply = reply;
        return Flux.just(
            events.responseCreated(responseId, sessionId),
            events.responseInProgress(responseId, sessionId),
            events.textMessageInProgress(messageId),
            events.contentDelta(messageId, finalReply),
            events.contentFinal(messageId, finalReply),
            events.textMessageCompleted(messageId, finalReply),
            events.responseCompleted(
                responseId, sessionId, 0, 0, completed
            ),
            events.turnUsage(sessionId, 0, 0)
        ).doFinally(signal -> {
            try {
                saveConsoleMessages(chatId, textContent(prompt), completed, List.of());
                chats.setStatus(chatId, "idle");
                tasks.setDone(chatId);
            } catch (Exception exception) {
                log.error("Failed to persist memory command chat {}",
                    chatId, exception);
            }
        });
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
            case "ToolCallStartEvent":
                recordToolStart(event, state);
                return toolEvent(event, state, events, "calling");
            case "ToolCallDeltaEvent":
                recordToolDelta(event, state);
                return toolEvent(event, state, events, "calling");
            case "ToolCallEndEvent":
                List<String> toolEvents = toolEvent(event, state, events, "done");
                recordToolEnd(event, state);
                return toolEvents;
            case "ModelCallEndEvent":
                var usage = ((ModelCallEndEvent) event).getUsage();
                if (usage != null) {
                    state.inputTokens = usage.getInputTokens();
                    state.outputTokens = usage.getOutputTokens();
                }
                return List.of();
            default:
                return List.of();
        }
    }

    private static List<String> toolEvent(
        Object event, TurnState state, QwenEvents events, String status
    ) {
        try {
            String id = String.valueOf(event.getClass().getMethod(
                "getToolCallId").invoke(event));
            ToolCallAccumulator call = state.toolCalls.get(id);
            String name = call == null ? String.valueOf(event.getClass()
                .getMethod("getToolCallName").invoke(event)) : call.name;
            String input = call == null ? "" : call.input.toString();
            return List.of(events.toolCall(id, name, input, status));
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    /** Preserve the structured tool-call arguments emitted as deltas so the
     * loop doom gate can compare actual calls, not opaque unique call IDs. */
    private static void recordToolStart(Object event, TurnState state) {
        try {
            String id = String.valueOf(event.getClass().getMethod(
                "getToolCallId").invoke(event));
            String name = String.valueOf(event.getClass().getMethod(
                "getToolCallName").invoke(event));
            state.toolCalls.put(id, new ToolCallAccumulator(name));
        } catch (ReflectiveOperationException ignored) {
            // Ignore unknown event shapes; the loop remains safe.
        }
    }

    private static void recordToolDelta(Object event, TurnState state) {
        try {
            String id = String.valueOf(event.getClass().getMethod(
                "getToolCallId").invoke(event));
            ToolCallAccumulator call = state.toolCalls.get(id);
            if (call != null) {
                call.input.append(String.valueOf(event.getClass().getMethod(
                    "getDelta").invoke(event)));
            }
        } catch (ReflectiveOperationException ignored) {
            // Ignore unknown event shapes; the loop remains safe.
        }
    }

    private static void recordToolEnd(Object event, TurnState state) {
        state.hasToolCalls = true;
        try {
            String id = String.valueOf(event.getClass().getMethod(
                "getToolCallId").invoke(event));
            ToolCallAccumulator call = state.toolCalls.remove(id);
            String name = call == null ? String.valueOf(event.getClass()
                .getMethod("getToolCallName").invoke(event)) : call.name;
            String input = call == null ? "" : call.input.toString();
            state.lastToolCall = new LoopContext.ToolCallRecord(name,
                DoomLoopGate.hashArgs(input));
            state.completedToolCalls.add(Map.of(
                "id", id, "name", name, "input", input, "status", "done"
            ));
        } catch (ReflectiveOperationException ignored) {
            // Ignore unknown event shapes; hasToolCalls stays true.
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
        List<Map<String, Object>> userContent,
        List<Map<String, Object>> completed,
        List<Map<String, Object>> toolCalls
    ) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", mapper.writeValueAsString(userContent));
        messages.add(user);
        if (!toolCalls.isEmpty()) {
            Map<String, String> assistantTools = new LinkedHashMap<>();
            assistantTools.put("role", "assistant");
            assistantTools.put("content", "[]");
            assistantTools.put("toolCalls", mapper.writeValueAsString(toolCalls));
            messages.add(assistantTools);
        }
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractUserContent(
        Map<String, Object> body, String fallbackPrompt
    ) {
        Object input = body.get("input");
        if (input instanceof List<?> messages && !messages.isEmpty()
            && messages.getFirst() instanceof Map<?, ?> message
            && message.get("content") instanceof List<?> rawParts) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object raw : rawParts) {
                if (!(raw instanceof Map<?, ?> rawPart)) continue;
                String type = text(rawPart.get("type"), "");
                if ("text".equals(type)) {
                    parts.add(Map.of("type", "text", "text",
                        text(rawPart.get("text"), "")));
                } else if ("image".equals(type)) {
                    String path = text(rawPart.get("image_url"), "");
                    if (!path.isBlank()) parts.add(Map.of("type", "image",
                        "image_url", path, "name", text(rawPart.get("name"), "")));
                } else if ("file".equals(type)) {
                    String path = text(rawPart.get("file_url"), "");
                    if (!path.isBlank()) parts.add(Map.of("type", "file",
                        "file_url", path, "name", text(rawPart.get("name"), "")));
                }
            }
            if (!parts.isEmpty()) return parts;
        }
        return textContent(fallbackPrompt);
    }

    private static List<Map<String, Object>> textContent(String value) {
        return List.of(Map.of("type", "text", "text", value));
    }

    private static List<Map<String, Object>> withPrompt(
        List<Map<String, Object>> content, String prompt
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> part : content) {
            if (!replaced && "text".equals(text(part.get("type"), ""))) {
                result.add(Map.of("type", "text", "text", prompt));
                replaced = true;
            } else {
                result.add(new LinkedHashMap<>(part));
            }
        }
        if (!replaced) result.addFirst(Map.of("type", "text", "text", prompt));
        return result;
    }

    private UserMessage buildUserMessage(List<Map<String, Object>> parts) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String type = text(part.get("type"), "");
            if ("text".equals(type)) {
                blocks.add(TextBlock.builder().text(text(part.get("text"), "")).build());
                continue;
            }
            String requested = "image".equals(type)
                ? text(part.get("image_url"), "") : text(part.get("file_url"), "");
            Path upload = resolveUpload(requested);
            if (upload == null) {
                blocks.add(TextBlock.builder().text("[Attachment is unavailable]").build());
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(upload);
                if (bytes.length > 20L * 1024 * 1024) {
                    blocks.add(TextBlock.builder().text(
                        "[Attachment omitted because it exceeds the 20 MB model-context limit: "
                            + upload.getFileName()).build());
                    continue;
                }
                String mime = mimeType(upload);
                Base64Source source = new Base64Source(mime,
                    Base64.getEncoder().encodeToString(bytes));
                if ("image".equals(type) || mime.startsWith("image/")) {
                    blocks.add(ImageBlock.builder().source(source).build());
                } else {
                    String name = text(part.get("name"), upload.getFileName().toString());
                    blocks.add(DataBlock.builder().source(source).name(name).build());
                }
            } catch (Exception exception) {
                blocks.add(TextBlock.builder().text("[Attachment could not be read: "
                    + upload.getFileName() + "]").build());
            }
        }
        return new UserMessage(blocks.isEmpty() ? List.of(
            TextBlock.builder().text("").build()) : blocks);
    }

    private Path resolveUpload(String requested) {
        if (requested == null || requested.isBlank()) return null;
        Path name;
        try { name = Path.of(requested).getFileName(); }
        catch (Exception exception) { return null; }
        if (name == null) return null;
        Path directory = workingDirectory.resolve("uploads").toAbsolutePath().normalize();
        Path target = directory.resolve(name.toString()).normalize();
        return target.startsWith(directory) && Files.isRegularFile(target) ? target : null;
    }

    private static String mimeType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            if (detected != null && !detected.isBlank()) return detected;
        } catch (Exception ignored) { }
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
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
        boolean hasToolCalls;
        LoopContext.ToolCallRecord lastToolCall;
        final Map<String, ToolCallAccumulator> toolCalls =
            new LinkedHashMap<>();
        final List<Map<String, Object>> completedToolCalls = new ArrayList<>();
    }

    private static final class ToolCallAccumulator {
        final String name;
        final StringBuilder input = new StringBuilder();

        ToolCallAccumulator(String name) {
            this.name = name;
        }
    }
}
