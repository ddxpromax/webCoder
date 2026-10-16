package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.ddxpromax.webcoder.channel.impl.FeishuChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeishuChannelTests {
    @Test void requiresAppCredentialsBeforeOpeningLongConnection() {
        assertThatThrownBy(() -> new FeishuChannel().start(Map.of("app_id", "cli_test"), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app_secret");
    }
}
