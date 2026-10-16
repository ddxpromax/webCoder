package io.github.ddxpromax.webcoder.channel;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Majo's built-in adapter catalogue; unsupported protocols fail explicitly at startup. */
@Configuration
public class ChannelAdaptersConfiguration {
    @Bean Channel consoleChannel() { return new ConsoleChannel(); }
    @Bean Channel imessageChannel() { return unsupported("imessage", "iMessage requires macOS integration."); }
    @Bean Channel mqttChannel() { return unsupported("mqtt", "MQTT requires an MQTT client integration."); }
    @Bean Channel voiceChannel() { return unsupported("voice", "Voice requires a telephony integration."); }
    @Bean Channel sipChannel() { return unsupported("sip", "SIP requires a gateway integration."); }
    @Bean Channel xiaoyiChannel() { return unsupported("xiaoyi", "Xiaoyi uses a private protocol."); }
    @Bean Channel yuanbaoChannel() { return unsupported("yuanbao", "Yuanbao requires official integration."); }
    private static Channel unsupported(String id, String reason) { return new UnsupportedChannel(id, reason); }

    private static final class ConsoleChannel implements Channel {
        @Override public String id() { return "console"; }
        @Override public void start(Map<String, Object> config, ChannelDispatcher dispatcher) { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return true; }
    }
    private record UnsupportedChannel(String id, String reason) implements Channel {
        @Override public void start(Map<String, Object> config, ChannelDispatcher dispatcher) { throw new IllegalStateException(reason); }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
    }
}
