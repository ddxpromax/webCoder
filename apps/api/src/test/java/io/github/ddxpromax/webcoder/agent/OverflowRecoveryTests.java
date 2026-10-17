package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OverflowRecoveryTests {
    @Test
    void recognizesCommonProviderContextOverflowMessagesThroughCauses() {
        assertThat(OverflowRecovery.isOverflow(new IllegalStateException(
            "Request exceeds maximum context window"
        ))).isTrue();
        assertThat(OverflowRecovery.isOverflow(new RuntimeException("outer",
            new IllegalArgumentException("input length exceeds the model limit")
        ))).isTrue();
        assertThat(OverflowRecovery.isOverflow(new IllegalStateException(
            "connection reset by peer"
        ))).isFalse();
    }
}
