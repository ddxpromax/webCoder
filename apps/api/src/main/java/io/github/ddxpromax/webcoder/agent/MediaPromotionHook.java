package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/** Promotes media emitted by a tool into the next model request, like Majo. */
public class MediaPromotionHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent acting)) return Mono.just(event);
        ToolResultBlock result = acting.getToolResult();
        if (result == null || result.getOutput() == null) return Mono.just(event);
        List<ContentBlock> promoted = new ArrayList<>();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof ImageBlock || block instanceof TextBlock) promoted.add(block);
        }
        if (promoted.stream().noneMatch(ImageBlock.class::isInstance)) return Mono.just(event);
        try {
            var agent = event.getAgent();
            if (agent != null && agent.getAgentState() != null) {
                agent.getAgentState().contextMutable().add(Msg.builder().name("user")
                    .role(MsgRole.USER).content(promoted).build());
            }
        } catch (Exception ignored) {
            // A failed promotion must not invalidate the successful tool call.
        }
        return Mono.just(event);
    }

    @Override public int priority() { return 20; }
}
