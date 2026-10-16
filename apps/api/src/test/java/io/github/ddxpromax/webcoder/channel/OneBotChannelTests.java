package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.OneBotChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OneBotChannelTests {
    @Test void requiresWebSocketHostBeforeOpeningConnection() {
        assertThatThrownBy(() -> new OneBotChannel().start(Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ws_host");
    }
}
