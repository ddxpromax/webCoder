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

/** Majo WeCom adapter: callback receive path and robot-webhook send path. */
@Component
public class WecomChannel implements Channel {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    @Override public String id() { return "wecom"; }
    @Override public void start(Map<String, Object> config, ChannelDispatcher dispatcher) { running.set(true); }
    @Override public void stop() { running.set(false); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String recipient, String text) {
        String webhook = value(config, "webhook_url");
        if (webhook.isBlank()) return "WeCom webhook_url is required.";
        try {
            String payload = mapper.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "WeCom returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) { return exception.getMessage(); }
    }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
