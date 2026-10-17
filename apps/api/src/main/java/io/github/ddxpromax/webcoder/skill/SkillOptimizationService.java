package io.github.ddxpromax.webcoder.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Streams Majo-compatible, model-assisted skill optimization events. */
@Service
public class SkillOptimizationService {
    private static final String SYSTEM_PROMPT = """
        You are an AI skill optimization expert. Optimize the supplied skill.

        Output the skill content directly. Do not use code fences or add an explanation.
        Preserve YAML frontmatter. Use a lowercase underscore-separated name, a clear
        description under 80 characters, and concise, well-structured Markdown.
        Keep the result within 500 characters when possible.
        """;

    private final ModelRoutingService models;
    private final ObjectMapper mapper;

    public SkillOptimizationService(
        ModelRoutingService models,
        ObjectMapper mapper
    ) {
        this.models = models;
        this.mapper = mapper;
    }

    public Flux<String> stream(String content, String agentId) {
        var slot = models.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return Flux.just(json(error(
                "No AI model configured. Please configure a model in Settings."
            )));
        }
        var model = models.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        List<Msg> messages = List.of(
            Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(SYSTEM_PROMPT).build()).build(),
            Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(content == null ? "" : content).build()).build()
        );
        StringBuilder accumulated = new StringBuilder();
        return model.stream(messages, List.of(), null)
            .map(response -> delta(response, accumulated))
            .filter(Objects::nonNull)
            .map(text -> json(java.util.Map.of("text", text)))
            .concatWithValues(json(java.util.Map.of("done", true)))
            .onErrorResume(exception -> Flux.just(json(error(
                "Failed to optimize skill: " + message(exception)
            ))));
    }

    private static String delta(
        io.agentscope.core.model.ChatResponse response,
        StringBuilder accumulated
    ) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object block : response.getContent()) {
            if (block instanceof TextBlock item && item.getText() != null) {
                text.append(item.getText());
            }
        }
        if (text.length() <= accumulated.length()) {
            return null;
        }
        String result = text.substring(accumulated.length());
        accumulated.setLength(0);
        accumulated.append(text);
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode skill optimization event", exception);
        }
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    }

    private static java.util.Map<String, String> error(String value) {
        return java.util.Map.of("error", value);
    }
}
