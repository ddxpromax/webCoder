package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;

/** Collects the final text of an independent agent turn. */
public final class StreamTextCollector {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();

    public void accept(Object event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            thinking.append(delta.getDelta());
        }
    }

    public String text() {
        return text.toString();
    }

    public String thinking() {
        return thinking.toString();
    }
}
