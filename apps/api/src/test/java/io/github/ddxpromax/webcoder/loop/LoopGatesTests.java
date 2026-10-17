package io.github.ddxpromax.webcoder.loop;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LoopGatesTests {
    @Test void goalContinuesUntilCompletionOrConfiguredLimit() {
        LoopSessionManager manager = new LoopSessionManager();
        StopHandler handler = new StopHandler();
        handler.register(new IterationGate(3));
        handler.register(new CompletionGate(null, 3));
        var goal = manager.start("one", "goal", "goal", "ship it", handler);
        LoopGate.setCurrentSession("one");
        try {
            assertThat(handler.run(new LoopContext(1, false,
                "I inspected the repository.", null, 10)).isContinue())
                .isTrue();
            assertThat(handler.run(new LoopContext(2, false,
                "Task COMPLETED.", null, 10)).isTerminate())
                .isTrue();
        } finally {
            LoopGate.clearCurrentSession();
        }
        assertThat(manager.status("one").get("state")).isEqualTo("running");
        manager.end("one");
        assertThat(manager.status("one").get("state")).isEqualTo("idle");
    }

    @Test void customCompilerRejectsConflictingCompletionGates() {
        var mode = java.util.Map.<String, Object>of("gates", java.util.List.of(
            java.util.Map.of("type", "qualitative_rubric", "enabled", true,
                "params", java.util.Map.of()),
            java.util.Map.of("type", "completion_rubric", "enabled", true,
                "params", java.util.Map.of())
        ));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            LoopCompiler.defaultCompiler().compile(mode)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
