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
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Majo Discord Gateway v10 adapter with IDENTIFY, heartbeat, and REST replies. */
@Component
public class DiscordChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int INTENTS = (1 << 9) | (1 << 12) | (1 << 15);
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile Thread connector;
    private volatile Thread heartbeat;

    @Override public String id() { return "discord"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "bot_token").isBlank()) throw new IllegalArgumentException("Discord bot_token is required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true);
        connector = new Thread(this::connectLoop, "discord-channel-gateway"); connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() { running.set(false); if (connector != null) connector.interrupt(); if (heartbeat != null) heartbeat.interrupt(); connector = null; heartbeat = null; WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String channel, String text) {
        try {
            String payload = mapper.writeValueAsString(Map.of("content", text));
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/channels/" + channel + "/messages")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bot " + value(config, "bot_token")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "Discord returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) { return exception.getMessage(); }
    }
    private void connectLoop() {
        while (running.get()) {
            try { connect(gateway()); } catch (Exception exception) { if (running.get()) log.warn("Discord connection failed: {}", exception.getMessage()); }
            pause();
        }
    }
    private String gateway() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/gateway/bot")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bot " + value(config, "bot_token")).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Discord gateway request returned HTTP " + response.statusCode() + ".");
        String url = mapper.readTree(response.body()).path("url").asText(); if (url.isBlank()) throw new IllegalStateException("Discord gateway response did not contain a URL."); return url;
    }
    private void connect(String gateway) {
        AtomicLong interval = new AtomicLong(41_000);
        WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(gateway + "?v=10&encoding=json"), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) { buffer.append(chunk); if (last) { String payload = buffer.toString(); buffer.setLength(0); try { handle(mapper.readTree(payload), webSocket, interval); } catch (Exception exception) { log.warn("Invalid Discord gateway message: {}", exception.getMessage()); } } webSocket.request(1); return null; }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("Discord socket error: {}", error.getMessage()); }
        }).join();
        socket = connected;
        try { while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void handle(JsonNode message, WebSocket current, AtomicLong interval) {
        switch (message.path("op").asInt(-1)) {
            case 10 -> { interval.set(message.path("d").path("heartbeat_interval").asLong(41_000)); startHeartbeat(current, interval); identify(current); }
            case 0 -> dispatch(message);
            case 7 -> current.abort();
            default -> { }
        }
    }
    private synchronized void startHeartbeat(WebSocket current, AtomicLong interval) {
        if (heartbeat != null && heartbeat.isAlive()) return;
        heartbeat = new Thread(() -> { while (running.get() && !current.isOutputClosed()) try { Thread.sleep(interval.get()); if (running.get()) current.sendText("{\"op\":1,\"d\":null}", true).join(); } catch (InterruptedException exception) { return; } catch (Exception exception) { return; } }, "discord-channel-heartbeat"); heartbeat.setDaemon(true); heartbeat.start();
    }
    private void identify(WebSocket current) { try { current.sendText(mapper.writeValueAsString(Map.of("op", 2, "d", Map.of("token", value(config, "bot_token"), "intents", INTENTS, "properties", Map.of("os", "java", "browser", "majo", "device", "majo")))), true).join(); } catch (Exception exception) { log.warn("Discord identify failed: {}", exception.getMessage()); } }
    private void dispatch(JsonNode message) {
        if (!"MESSAGE_CREATE".equals(message.path("t").asText())) return;
        JsonNode body = message.path("d"); if (body.path("author").path("bot").asBoolean()) return;
        String channel = body.path("channel_id").asText(), sender = body.path("author").path("id").asText(), text = body.path("content").asText();
        if (channel.isBlank() || sender.isBlank() || text.isBlank()) return;
        String group = body.path("guild_id").isNull() ? null : body.path("guild_id").asText();
        dispatcher.dispatch(config, new ChannelMessage(id(), sender, body.path("author").path("username").asText(sender), group, text, channel), (recipient, reply) -> { String error = sendText(config, recipient, reply); if (error != null) log.warn("Discord reply failed: {}", error); });
    }
    private void pause() { try { Thread.sleep(10_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
