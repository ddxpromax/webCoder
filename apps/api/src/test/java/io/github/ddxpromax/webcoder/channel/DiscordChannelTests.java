package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.DiscordChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscordChannelTests {
    @Test void requiresBotTokenBeforeConnectingGateway() {
        assertThatThrownBy(() -> new DiscordChannel().start(Map.of(), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bot_token");
    }
}
