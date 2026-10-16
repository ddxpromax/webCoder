package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Majo QQ Official Bot send adapter; inbound traffic uses the QQ webhook. */
@Component
public class QQChannel implements Channel {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicLong expiry = new AtomicLong();
    private final AtomicInteger sequence = new AtomicInteger(1);
    @Override public String id() { return "qq"; }
    @Override public void start(Map<String, Object> config, ChannelDispatcher dispatcher) { running.set(true); }
    @Override public void stop() { running.set(false); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String recipient, String text) { return send(config, recipient, text, false); }
    public String sendGroupText(Map<String, Object> config, String recipient, String text) { return send(config, recipient, text, true); }
    private String send(Map<String, Object> config, String recipient, String text, boolean group) {
        try {
            String payload = mapper.writeValueAsString(Map.of("content", text, "msg_type", 0, "msg_seq", sequence.getAndIncrement()));
            String kind = group ? "groups" : "users";
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.sgroup.qq.com/v2/" + kind + "/" + recipient + "/messages")).timeout(Duration.ofSeconds(20)).header("Authorization", "QQBot " + accessToken(config)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "QQ returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) { return exception.getMessage(); }
    }
    private String accessToken(Map<String, Object> config) throws Exception {
        String cached = token.get();
        if (cached != null && Instant.now().toEpochMilli() < expiry.get() - 60_000) return cached;
        String appId = value(config, "app_id"), secret = value(config, "client_secret");
        if (appId.isBlank() || secret.isBlank()) throw new IllegalArgumentException("QQ app_id and client_secret are required.");
        String payload = mapper.writeValueAsString(Map.of("appId", appId, "clientSecret", secret));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://bots.qq.com/app/getAppAccessToken")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("QQ access token request returned HTTP " + response.statusCode() + ".");
        JsonNode root = mapper.readTree(response.body()); String next = root.path("access_token").asText();
        if (next.isBlank()) throw new IllegalStateException("QQ access token response was empty.");
        token.set(next); expiry.set(Instant.now().toEpochMilli() + root.path("expires_in").asLong(7200) * 1000); return next;
    }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
