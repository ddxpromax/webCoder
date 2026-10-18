package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** JSON event shapes accepted by Majo's console chat client. */
public class QwenEvents {
    private final ObjectMapper mapper;
    private final AtomicLong sequence = new AtomicLong(1);

    public QwenEvents(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String responseCreated(String id, String sessionId) {
        return responseState(id, sessionId, "created");
    }

    public String responseInProgress(String id, String sessionId) {
        return responseState(id, sessionId, "in_progress");
    }

    public String reasoningInProgress(String messageId) {
        return messageState(messageId, "reasoning", "in_progress",
            null, false);
    }

    public String reasoningCompleted(
        String messageId,
        String fullText
    ) {
        return messageState(messageId, "reasoning", "completed",
            fullText, false);
    }

    public String textMessageInProgress(String messageId) {
        return messageState(messageId, "message", "in_progress",
            null, false);
    }

    public String textMessageCompleted(
        String messageId,
        String fullText
    ) {
        return messageState(messageId, "message", "completed",
            fullText, true);
    }

    public String contentDelta(String messageId, String delta) {
        return content(messageId, delta, true);
    }

    public String contentFinal(String messageId, String fullText) {
        return content(messageId, fullText, false);
    }

    /** Generic Majo-compatible tool-call progress event for console clients. */
    public String toolCall(String id, String name, String input, String result,
                           String status) {
        ObjectNode event = mapper.createObjectNode();
        event.put("object", "tool_call");
        event.put("id", id);
        event.put("name", name);
        event.put("input", input);
        if (result != null && !result.isEmpty()) {
            event.put("result", result);
        }
        event.put("status", status);
        event.put("sequence_number", sequence.getAndIncrement());
        return event.toString();
    }

    public String toolCall(String id, String name, String input, String status) {
        return toolCall(id, name, input, null, status);
    }

    public String responseCompleted(
        String responseId,
        String sessionId,
        int inputTokens,
        int outputTokens,
        List<Map<String, Object>> messages
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", responseId);
        ArrayNode output = mapper.valueToTree(messages);
        event.set("output", output);
        event.put("status", "completed");
        event.put("created_at", Instant.now().toString());
        event.put("completed_at", Instant.now().toString());
        event.putNull("metadata");
        event.put("object", "response");
        event.put("session_id", sessionId);
        event.put("sequence_number", sequence.getAndIncrement());
        ObjectNode usage = mapper.createObjectNode();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        event.set("usage", usage);
        return event.toString();
    }

    public String turnUsage(
        String sessionId,
        int inputTokens,
        int outputTokens
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "turn_usage");
        event.put("session_id", sessionId);
        ObjectNode usage = mapper.createObjectNode();
        usage.put("provider_id", "");
        usage.put("model_name", "");
        usage.put("prompt_tokens", inputTokens);
        usage.put("completion_tokens", outputTokens);
        usage.put("total_tokens", inputTokens + outputTokens);
        usage.put("context_size", 131072);
        usage.put("compact_threshold", 0.8);
        event.set("usage", usage);
        event.put("sequence_number", sequence.getAndIncrement());
        return event.toString();
    }

    public String error(String content) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "error");
        event.put("content", content);
        return event.toString();
    }

    private String responseState(
        String id,
        String sessionId,
        String status
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", id);
        event.set("output", mapper.createArrayNode());
        event.put("status", status);
        event.put("created_at", Instant.now().toString());
        event.putNull("completed_at");
        event.putNull("metadata");
        event.put("object", "response");
        event.put("session_id", sessionId);
        event.put("sequence_number", sequence.getAndIncrement());
        return event.toString();
    }

    private String messageState(
        String messageId,
        String type,
        String status,
        String fullText,
        boolean withUsage
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", messageId);
        event.put("type", type);
        event.put("role", "assistant");
        ArrayNode content = mapper.createArrayNode();
        if (fullText != null) {
            ObjectNode part = mapper.createObjectNode();
            part.put("type", "text");
            part.put("delta", false);
            part.put("index", 0);
            part.putNull("status");
            part.put("object", "content");
            part.put("text", fullText);
            content.add(part);
        }
        event.set("content", content);
        event.put("status", status);
        event.putNull("metadata");
        event.put("name", "assistant");
        event.put("object", "message");
        event.put("sequence_number", sequence.getAndIncrement());
        if (withUsage) {
            ObjectNode usage = mapper.createObjectNode();
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
            event.set("usage", usage);
        }
        return event.toString();
    }

    private String content(
        String messageId,
        String text,
        boolean isDelta
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "text");
        event.put("delta", isDelta);
        event.put("index", 0);
        event.putNull("status");
        event.put("object", "content");
        event.put("msg_id", messageId);
        event.put("text", text);
        event.put("sequence_number", sequence.getAndIncrement());
        return event.toString();
    }
}
