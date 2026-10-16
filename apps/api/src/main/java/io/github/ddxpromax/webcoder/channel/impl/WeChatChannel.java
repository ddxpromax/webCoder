package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Majo WeChat relay adapter; inbound traffic arrives through the webhook controller. */
@Component
public class WeChatChannel implements Channel {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    @Override public String id() { return "wechat"; }
    @Override public void start(Map<String, Object> config, ChannelDispatcher dispatcher) { running.set(true); }
    @Override public void stop() { running.set(false); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String recipient, String text) {
        String base = value(config, "base_url"), token = value(config, "bot_token");
        if (base.isBlank() || token.isBlank()) return "WeChat base_url and bot_token are required.";
        try {
            URI uri = URI.create((base.endsWith("/") ? base : base + "/") + "send");
            String payload = mapper.writeValueAsString(Map.of("token", token, "receiver", recipient, "text", text));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "WeChat relay returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) { return exception.getMessage(); }
    }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
