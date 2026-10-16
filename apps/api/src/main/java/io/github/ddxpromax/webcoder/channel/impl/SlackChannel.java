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

/** Majo Slack Socket Mode adapter using envelope ACKs and chat.postMessage. */
@Component
public class SlackChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile Thread connector;
    @Override public String id() { return "slack"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "bot_token").isBlank() || value(config, "app_token").isBlank()) throw new IllegalArgumentException("Slack bot_token and app_token are required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true); connector = new Thread(this::connectLoop, "slack-channel-socket"); connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() { running.set(false); if (connector != null) connector.interrupt(); connector = null; WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public String sendText(Map<String, Object> config, String channel, String text) {
        try {
            String payload = mapper.writeValueAsString(Map.of("channel", channel, "text", text));
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://slack.com/api/chat.postMessage")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + value(config, "bot_token")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(response.body()); return result.path("ok").asBoolean() ? null : result.path("error").asText("Slack message failed.");
        } catch (Exception exception) { return exception.getMessage(); }
    }
    private void connectLoop() { while (running.get()) { try { connect(socketUrl()); } catch (Exception exception) { if (running.get()) log.warn("Slack connection failed: {}", exception.getMessage()); } pause(); } }
    private String socketUrl() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://slack.com/api/apps.connections.open")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + value(config, "app_token")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = mapper.readTree(response.body()); if (!result.path("ok").asBoolean()) throw new IllegalStateException("Slack connection request failed: " + result.path("error").asText());
        String url = result.path("url").asText(); if (url.isBlank()) throw new IllegalStateException("Slack did not return a socket URL."); return url;
    }
    private void connect(String url) {
        WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) { buffer.append(chunk); if (last) { String payload = buffer.toString(); buffer.setLength(0); try { handle(mapper.readTree(payload), webSocket); } catch (Exception exception) { log.warn("Invalid Slack envelope: {}", exception.getMessage()); } } webSocket.request(1); return null; }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("Slack socket error: {}", error.getMessage()); }
        }).join(); socket = connected;
        try { while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void handle(JsonNode envelope, WebSocket current) {
        String envelopeId = envelope.path("envelope_id").asText(); if (!envelopeId.isBlank()) try { current.sendText(mapper.writeValueAsString(Map.of("envelope_id", envelopeId)), true).join(); } catch (Exception exception) { log.warn("Slack ACK failed: {}", exception.getMessage()); }
        if (!"events_api".equals(envelope.path("type").asText())) return;
        JsonNode event = envelope.path("payload").path("event");
        if (!"message".equals(event.path("type").asText()) || !event.path("subtype").isMissingNode() || !event.path("bot_id").isMissingNode()) return;
        String user = event.path("user").asText(), channel = event.path("channel").asText(), text = event.path("text").asText(); if (user.isBlank() || channel.isBlank() || text.isBlank()) return;
        String group = "im".equals(event.path("channel_type").asText("im")) ? null : channel;
        dispatcher.dispatch(config, new ChannelMessage(id(), user, user, group, text, channel), (recipient, reply) -> { String error = sendText(config, recipient, reply); if (error != null) log.warn("Slack reply failed: {}", error); });
    }
    private void pause() { try { Thread.sleep(10_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
