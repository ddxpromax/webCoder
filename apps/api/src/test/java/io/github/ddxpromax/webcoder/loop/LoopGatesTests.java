package io.github.ddxpromax.webcoder.loop;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LoopGatesTests {
    @Test void goalContinuesUntilCompletionOrConfiguredLimit() {
        LoopSessionManager manager = new LoopSessionManager();
        var goal = manager.start("one", "goal", "goal", "ship it", 3, 1000);
        assertThat(LoopGates.afterTurn(goal, "I inspected the repository.", 10).continueLoop()).isTrue();
        assertThat(LoopGates.afterTurn(goal, "Completed the requested change.", 10).continueLoop()).isFalse();
        assertThat(manager.status("one").get("state")).isEqualTo("running");
        manager.end("one");
        assertThat(manager.status("one").get("state")).isEqualTo("idle");
    }
}
