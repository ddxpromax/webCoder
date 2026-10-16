package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.DingTalkChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DingTalkChannelTests {
    @Test void requiresClientCredentialsBeforeOpeningStreamConnection() {
        assertThatThrownBy(() -> new DingTalkChannel().start(Map.of("client_id", "ding-test"), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client_secret");
    }
}
