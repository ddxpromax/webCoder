package io.github.ddxpromax.webcoder.agent;

import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * LLM-backed {@link MemorySummarizer} (ADR-0014): one small call against
 * the agent's effective model, mirroring QwenPaw's ReMe auto_memory
 * extraction. Any failure propagates — the summary backend falls back to
 * the raw excerpt so a turn is never lost.
 */
@Component
public class LlmMemorySummarizer implements MemorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmMemorySummarizer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final String SYSTEM_PROMPT = """
            You curate durable memory from conversation excerpts. Extract only
            user preferences, project facts, environment details, and explicit
            decisions worth remembering across sessions. Ignore tool output,
            intermediate reasoning, and small talk. Return up to ten concise
            bullets, one per line. If nothing is worth keeping, reply NONE.
            """;

    private final ModelRoutingService modelRouting;

    public LlmMemorySummarizer(ModelRoutingService modelRouting) {
        this.modelRouting = modelRouting;
    }

    @Override
    public Optional<String> summarize(String agentId, String content) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            log.warn("[memory-summary] no effective model for agent '{}' — extraction skipped", agentId);
            return Optional.empty();
        }
        OpenAIChatModel model = modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        List<Msg> msgs = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(SYSTEM_PROMPT).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text(content).build()).build());
        // Streaming responses deliver text across MANY ChatResponse chunks
        // (the last one often carries only usage) — aggregate them all.
        List<io.agentscope.core.model.ChatResponse> responses =
                model.stream(msgs, List.of(), null).collectList().block(TIMEOUT);
        String text = extractText(responses);
        if (text == null) {
            log.warn("[memory-summary] agent '{}' model returned no text blocks (chunks={})",
                    agentId, responses == null ? -1 : responses.size());
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || "NONE".equalsIgnoreCase(trimmed)) {
            log.info("[memory-summary] agent '{}' model judged batch not worth keeping", agentId);
            return Optional.empty();
        }
        if (trimmed.length() > MAX_SUMMARY_CHARS) {
            trimmed = trimmed.substring(0, MAX_SUMMARY_CHARS) + "\n...[summary truncated]";
        }
        log.debug("[memory-summary] extracted {} chars for agent '{}'", trimmed.length(), agentId);
        return Optional.of(trimmed);
    }

    private static String extractText(List<io.agentscope.core.model.ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (var response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            for (var block : response.getContent()) {
                if (block instanceof TextBlock t && t.getText() != null) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
