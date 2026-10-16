package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.TelegramChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramChannelTests {
    @Test void requiresBotTokenBeforeStartingNetworkPolling() {
        assertThatThrownBy(() -> new TelegramChannel().start(Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bot_token");
    }
}
