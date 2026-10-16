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

/** Majo Mattermost adapter: real-time WebSocket receive and REST post replies. */
@Component
public class MattermostChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(MattermostChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile String selfId = "";
    private volatile Thread connector;

    @Override public String id() { return "mattermost"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "url").isBlank() || value(config, "bot_token").isBlank()) throw new IllegalArgumentException("Mattermost url and bot_token are required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true);
        connector = new Thread(this::connectLoop, "mattermost-channel-ws"); connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() { running.set(false); if (connector != null) connector.interrupt(); connector = null; WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String channel, String text) {
        try {
            String payload = mapper.writeValueAsString(Map.of("channel_id", channel, "message", text));
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base(config) + "/api/v4/posts")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + value(config, "bot_token")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "Mattermost returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) { return exception.getMessage(); }
    }

    private void connectLoop() {
        while (running.get()) {
            try { selfId = currentUser(); connect(); }
            catch (Exception exception) { if (running.get()) log.warn("Mattermost connection failed: {}", exception.getMessage()); }
            pause();
        }
    }
    private String currentUser() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base(config) + "/api/v4/users/me")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + value(config, "bot_token")).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Mattermost users/me returned HTTP " + response.statusCode() + ".");
        return mapper.readTree(response.body()).path("id").asText();
    }
    private void connect() {
        String endpoint = base(config).replaceFirst("^http", "ws") + "/api/v4/websocket?access_token=" + value(config, "bot_token");
        WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(endpoint), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) { buffer.append(chunk); if (last) { String data = buffer.toString(); buffer.setLength(0); try { dispatch(mapper.readTree(data)); } catch (Exception exception) { log.warn("Invalid Mattermost event: {}", exception.getMessage()); } } webSocket.request(1); return null; }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("Mattermost socket error: {}", error.getMessage()); }
        }).join();
        socket = connected;
        try { while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void dispatch(JsonNode event) {
        if (!"posted".equals(event.path("event").asText())) return;
        try {
            JsonNode post = mapper.readTree(event.path("data").path("post").asText("{}"));
            String sender = post.path("user_id").asText(), channel = post.path("channel_id").asText(), text = post.path("message").asText();
            if (sender.isBlank() || sender.equals(selfId) || channel.isBlank() || text.isBlank()) return;
            String group = "D".equals(event.path("data").path("channel_type").asText("D")) ? null : channel;
            dispatcher.dispatch(config, new ChannelMessage(id(), sender, sender, group, text, channel), (recipient, reply) -> { String error = sendText(config, recipient, reply); if (error != null) log.warn("Mattermost reply failed: {}", error); });
        } catch (Exception exception) { log.warn("Unable to parse Mattermost post: {}", exception.getMessage()); }
    }
    private void pause() { try { Thread.sleep(10_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String base(Map<String, Object> config) { String url = value(config, "url"); return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
