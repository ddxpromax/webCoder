package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.MatrixChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatrixChannelTests {
    @Test void requiresServerAndAccessTokenBeforeStartingSync() {
        assertThatThrownBy(() -> new MatrixChannel().start(Map.of("homeserver", "https://matrix.example"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("access_token");
    }
}
