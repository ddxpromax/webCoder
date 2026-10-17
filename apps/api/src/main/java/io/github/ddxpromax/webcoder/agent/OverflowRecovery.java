package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Majo's one-shot, no-duplicate recovery path for provider context-overflow
 * errors. A retry is allowed only before meaningful output escaped the agent,
 * so an SSE client can never receive a replayed partial answer.
 */
public final class OverflowRecovery {
    private static final Logger log = LoggerFactory.getLogger(OverflowRecovery.class);
    private static final List<String> MARKERS = List.of(
        "context length", "context_length", "maximum context", "context window",
        "too many tokens", "token limit", "prompt is too long", "request too large",
        "input length exceeds", "reduce the length", "overflow"
    );

    private OverflowRecovery() { }

    public static Flux<AgentEvent> recover(Flux<AgentEvent> source, HarnessAgent agent, UserMessage prompt, RuntimeContext context) {
        AtomicBoolean emittedContent = new AtomicBoolean();
        AtomicBoolean retried = new AtomicBoolean();
        return source.doOnNext(event -> {
            String type = event.getClass().getSimpleName();
            if (type.contains("TextBlock")) emittedContent.set(true);
        }).onErrorResume(error -> {
            if (emittedContent.get() || !isOverflow(error) || !retried.compareAndSet(false, true)) return Flux.error(error);
            log.warn("Context overflow detected; compacting and retrying this turn once: {}", rootMessage(error));
            if (!compact(agent, prompt.getTextContent())) return Flux.error(error);
            return agent.streamEvents(prompt, context);
        });
    }

    static boolean isOverflow(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase();
            if (MARKERS.stream().anyMatch(normalized::contains)) return true;
        }
        return false;
    }

    static boolean compact(HarnessAgent agent, String promptText) {
        try {
            if (agent.getAgentState() == null || agent.getAgentState().contextMutable() == null) return false;
            List<Msg> state = agent.getAgentState().contextMutable();
            if (state.isEmpty()) return false;
            List<Msg> current = new ArrayList<>(state);
            boolean changed = false;
            Msg tail = current.getLast();
            if (tail.getRole() == MsgRole.USER && promptText != null && promptText.equals(tail.getTextContent())) {
                current.removeLast();
                changed = true;
            }
            List<Msg> bounded = new ArrayList<>(current.size());
            for (Msg message : current) {
                Msg result = ContextCompactor.boundToolResults(message);
                changed |= result != message;
                bounded.add(result);
            }
            List<Msg> folded = ContextCompactor.foldForPressure(bounded);
            changed |= folded != bounded;
            if (!changed) return false;
            state.clear();
            state.addAll(folded);
            return true;
        } catch (Exception exception) {
            log.warn("Could not compact agent context after overflow", exception);
            return false;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
