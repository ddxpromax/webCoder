package io.github.ddxpromax.webcoder.channel;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ChannelMessageTests {
    @Test void privateMessagesDefaultReplyTargetToSender() {
        ChannelMessage message = new ChannelMessage("telegram", "user-1", "Alice", null, "hello", null);
        assertThat(message.replyTo()).isEqualTo("user-1");
        assertThat(message.identity()).isEqualTo("user-1");
    }

    @Test void groupMessagesUseGroupAsReplyTargetAndSessionIdentity() {
        ChannelMessage message = new ChannelMessage("slack", "user-1", "Alice", "group-1", "hello", null);
        assertThat(message.replyTo()).isEqualTo("group-1");
        assertThat(message.identity()).isEqualTo("group-1:user-1");
    }
}
