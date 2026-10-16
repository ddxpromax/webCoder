package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import io.github.ddxpromax.webcoder.channel.ChannelMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Majo DingTalk Stream Mode adapter with gateway ACK and session-webhook replies. */
@Component
public class DingTalkChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile Thread connector;
    @Override public String id() { return "dingtalk"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "client_id").isBlank() || value(config, "client_secret").isBlank()) throw new IllegalArgumentException("DingTalk client_id and client_secret are required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true); connector = new Thread(this::connectLoop, "dingtalk-channel-stream"); connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() { running.set(false); if (connector != null) connector.interrupt(); connector = null; WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running.get(); }
    private void connectLoop() { while (running.get()) { try { String token = accessToken(); connect(connectionUrl(token), token); } catch (Exception exception) { if (running.get()) log.warn("DingTalk connection failed: {}", exception.getMessage()); } pause(); } }
    private String accessToken() throws Exception {
        String payload = mapper.writeValueAsString(Map.of("appKey", value(config, "client_id"), "appSecret", value(config, "client_secret")));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.dingtalk.com/v1.0/oauth2/accessToken")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("DingTalk access-token request returned HTTP " + response.statusCode() + ".");
        String token = mapper.readTree(response.body()).path("accessToken").asText(); if (token.isBlank()) throw new IllegalStateException("DingTalk access token was empty."); return token;
    }
    private String connectionUrl(String token) throws Exception {
        String payload = mapper.writeValueAsString(Map.of("clientId", value(config, "client_id"), "clientSecret", value(config, "client_secret"), "subscriptions", List.of(Map.of("topic", "/v1.0/im/bot/messages/get"))));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.dingtalk.com/v1.0/gateway/connections/open")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("x-acs-dingtalk-access-token", token).POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("DingTalk connection request returned HTTP " + response.statusCode() + ".");
        String url = mapper.readTree(response.body()).path("url").asText(); if (url.isBlank()) throw new IllegalStateException("DingTalk stream URL was empty."); return url;
    }
    private void connect(String url, String token) {
        WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) { buffer.append(chunk); if (last) { String payload = buffer.toString(); buffer.setLength(0); try { dispatch(mapper.readTree(payload), token); } catch (Exception exception) { log.warn("Invalid DingTalk event: {}", exception.getMessage()); } } webSocket.request(1); return null; }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("DingTalk socket error: {}", error.getMessage()); }
        }).join(); socket = connected;
        try { while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void dispatch(JsonNode payload, String token) throws Exception {
        if (!"/v1.0/im/bot/messages/get".equals(payload.path("topic").asText())) return;
        acknowledge(payload, token);
        JsonNode data = payload.path("data"); String text = data.path("text").path("content").asText();
        if (!"text".equals(data.path("msgtype").asText()) || text.isBlank()) return;
        String sender = data.path("senderStaffId").asText("unknown"), name = data.path("senderNick").asText(sender), group = "2".equals(data.path("conversationType").asText("1")) ? data.path("conversationId").asText() : null;
        String clean = text.replaceAll("@\\s*\\S+", "").trim(); String webhook = data.path("sessionWebhook").asText();
        if (clean.isBlank() || webhook.isBlank()) return;
        dispatcher.dispatch(config, new ChannelMessage(id(), sender, name, group, clean, null), (recipient, reply) -> reply(webhook, reply));
    }
    private void acknowledge(JsonNode payload, String token) {
        try {
            String body = mapper.writeValueAsString(Map.of("requestId", payload.path("eventId").asText(), "headers", payload.path("headers")));
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.dingtalk.com/v1.0/gateway/connections/ack")).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("x-acs-dingtalk-access-token", token).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) { log.warn("DingTalk ACK failed: {}", exception.getMessage()); }
    }
    private void reply(String webhook, String text) {
        try {
            String payload = mapper.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) log.warn("DingTalk reply returned HTTP {}", response.statusCode());
        } catch (Exception exception) { log.warn("DingTalk reply failed: {}", exception.getMessage()); }
    }
    private void pause() { try { Thread.sleep(10_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
