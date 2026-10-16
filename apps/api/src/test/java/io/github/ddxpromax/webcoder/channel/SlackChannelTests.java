package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.SlackChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackChannelTests {
    @Test void requiresBothSocketModeTokensBeforeConnecting() {
        assertThatThrownBy(() -> new SlackChannel().start(Map.of("bot_token", "xoxb-test"), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app_token");
    }
}
