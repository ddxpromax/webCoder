package io.github.ddxpromax.webcoder.channel;

import java.util.Map;

/** A Majo-compatible adapter for one external messaging channel. */
public interface Channel {
    String id();

    void start(Map<String, Object> config, ChannelDispatcher dispatcher);

    void stop();

    boolean isRunning();

    default String sendText(Map<String, Object> config, String recipient, String text) {
        return "Channel '" + id() + "' does not support sending messages.";
    }
}
