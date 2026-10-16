package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.MattermostChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MattermostChannelTests {
    @Test void requiresUrlAndBotTokenBeforeOpeningWebSocket() {
        assertThatThrownBy(() -> new MattermostChannel().start(Map.of("url", "https://mattermost.example"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bot_token");
    }
}
