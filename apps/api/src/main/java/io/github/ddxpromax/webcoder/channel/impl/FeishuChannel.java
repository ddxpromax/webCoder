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
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Majo Feishu/Lark long-connection adapter with tenant authentication and reply API. */
@Component
public class FeishuChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile Thread connector;
    @Override public String id() { return "feishu"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "app_id").isBlank() || value(config, "app_secret").isBlank()) throw new IllegalArgumentException("Feishu app_id and app_secret are required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true); connector = new Thread(this::connectLoop, "feishu-channel-ws"); connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() { running.set(false); if (connector != null) connector.interrupt(); connector = null; WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running.get(); }
    private void connectLoop() { while (running.get()) { try { String token = tenantToken(); connect(socketUrl(token), token); } catch (Exception exception) { if (running.get()) log.warn("Feishu connection failed: {}", exception.getMessage()); } pause(); } }
    private String tenantToken() throws Exception {
        String payload = mapper.writeValueAsString(Map.of("app_id", value(config, "app_id"), "app_secret", value(config, "app_secret")));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/open-apis/auth/v3/tenant_access_token/internal")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Feishu tenant-token request returned HTTP " + response.statusCode() + ".");
        String token = mapper.readTree(response.body()).path("tenant_access_token").asText(); if (token.isBlank()) throw new IllegalStateException("Feishu tenant token was empty."); return token;
    }
    private String socketUrl(String token) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/open-apis/events/websocket/connect")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Feishu socket request returned HTTP " + response.statusCode() + ".");
        String url = mapper.readTree(response.body()).path("data").path("ws_url").asText(); if (url.isBlank()) throw new IllegalStateException("Feishu socket URL was empty."); return url;
    }
    private void connect(String url, String token) {
        WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) { buffer.append(chunk); if (last) { String payload = buffer.toString(); buffer.setLength(0); try { dispatch(mapper.readTree(payload), token); } catch (Exception exception) { log.warn("Invalid Feishu event: {}", exception.getMessage()); } } webSocket.request(1); return null; }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("Feishu socket error: {}", error.getMessage()); }
        }).join(); socket = connected;
        try { while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void dispatch(JsonNode body, String token) throws Exception {
        if (!"im.message.receive_v1".equals(body.path("header").path("event_type").asText())) return;
        String expected = value(config, "verification_token"); if (!expected.isBlank() && !expected.equals(body.path("header").path("token").asText())) return;
        JsonNode event = body.path("event"), message = event.path("message");
        String messageId = message.path("message_id").asText(), chatId = message.path("chat_id").asText(), sender = event.path("sender").path("sender_id").path("open_id").asText();
        String text = mapper.readTree(message.path("content").asText("{}")).path("text").asText();
        if (messageId.isBlank() || sender.isBlank() || text.isBlank()) return;
        String group = "group".equals(message.path("chat_type").asText("p2p")) ? chatId : null;
        dispatcher.dispatch(config, new ChannelMessage(id(), sender, sender, group, text, messageId), (recipient, reply) -> reply(recipient, reply, token));
    }
    private void reply(String messageId, String text, String token) {
        try {
            String content = mapper.writeValueAsString(Map.of("text", text)); String payload = mapper.writeValueAsString(Map.of("msg_type", "text", "content", content));
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/open-apis/im/v1/messages/" + messageId + "/reply")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) log.warn("Feishu reply returned HTTP {}", response.statusCode());
        } catch (Exception exception) { log.warn("Feishu reply failed: {}", exception.getMessage()); }
    }
    private String base() { return "lark".equals(value(config, "domain")) ? "https://open.larksuite.com" : "https://open.feishu.cn"; }
    private void pause() { try { Thread.sleep(10_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
