package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** One agent turn's link to the durable Majo memory write pipeline. */
public class MemoryWriteHook implements Hook {
    private static final Logger log = LoggerFactory.getLogger(
        MemoryWriteHook.class
    );
    private final String agentId;
    private final MemoryWritePipeline pipeline;
    private String turnId;

    public MemoryWriteHook(
        String agentId,
        MemoryWritePipeline pipeline
    ) {
        this.agentId = agentId;
        this.pipeline = pipeline;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreCallEvent call) {
                String prompt = pipeline.memoryPrompt(agentId);
                if (prompt != null && !prompt.isBlank()) {
                    String existing = call.getSystemMessage() == null
                        ? "" : call.getSystemMessage()
                            .getTextContent();
                    if (!existing.contains("# Memory\n")) {
                        call.appendSystemContent(
                            "\n\n# Memory\n" + prompt
                        );
                    }
                }
                if (turnId == null) {
                    turnId = pipeline.beginTurn(
                        agentId,
                        latestUserText(call.getInputMessages())
                    );
                }
            } else if (event instanceof PostCallEvent call) {
                Msg reply = call.getFinalMessage();
                pipeline.finishTurn(
                    agentId,
                    turnId,
                    reply == null ? "" : reply.getTextContent()
                );
            }
        } catch (Exception exception) {
            log.debug("Memory write hook skipped for agent '{}'",
                agentId, exception);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 6;
    }

    private static String latestUserText(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message.getRole() == MsgRole.USER) {
                return message.getTextContent();
            }
        }
        return null;
    }
}
