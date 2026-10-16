package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import io.github.ddxpromax.webcoder.channel.ChannelMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Majo Matrix adapter based on Client-Server API sync long polling. */
@Component
public class MatrixChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(MatrixChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile String since;
    private volatile Thread poller;
    @Override public String id() { return "matrix"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "homeserver").isBlank() || value(config, "access_token").isBlank()) throw new IllegalArgumentException("Matrix homeserver and access_token are required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true);
        poller = new Thread(this::syncLoop, "matrix-channel-sync"); poller.setDaemon(true); poller.start();
    }
    @Override public synchronized void stop() { running.set(false); if (poller != null) poller.interrupt(); poller = null; }
    @Override public boolean isRunning() { return running.get(); }

    private void syncLoop() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        while (running.get()) try {
            String cursor = since == null ? "" : "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
            URI uri = URI.create(base() + "_matrix/client/v3/sync?timeout=30000" + cursor + "&access_token=" + encoded(value(config, "access_token")));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { pause(); continue; }
            JsonNode root = mapper.readTree(response.body()); since = root.path("next_batch").asText(since);
            root.path("rooms").path("join").fields().forEachRemaining(entry -> handleRoom(entry.getKey(), entry.getValue()));
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
        catch (Exception exception) { if (running.get()) log.warn("Matrix sync failed: {}", exception.getMessage()); pause(); }
    }
    private void handleRoom(String roomId, JsonNode room) {
        String self = value(config, "user_id");
        for (JsonNode event : room.path("timeline").path("events")) {
            if (!"m.room.message".equals(event.path("type").asText())) continue;
            String sender = event.path("sender").asText(), text = event.path("content").path("body").asText();
            String type = event.path("content").path("msgtype").asText();
            if (sender.isBlank() || sender.equals(self) || text.isBlank() || (!"m.text".equals(type) && !"m.notice".equals(type))) continue;
            String user = sender.startsWith("@") ? sender.substring(1) : sender;
            dispatcher.dispatch(config, new ChannelMessage(id(), user, user, roomId, text, roomId), (recipient, reply) -> sendReply(recipient, reply));
        }
    }
    @Override public String sendText(Map<String, Object> config, String roomId, String text) { try { send(config, roomId, text); return null; } catch (Exception exception) { return exception.getMessage(); } }
    private void sendReply(String roomId, String text) { String error = sendText(config, roomId, text); if (error != null) log.warn("Matrix send failed: {}", error); }
    private static void send(Map<String, Object> config, String roomId, String text) throws Exception {
        String path = base(config) + "_matrix/client/v3/rooms/" + encoded(roomId) + "/send/m.room.message/majo-" + UUID.randomUUID() + "?access_token=" + encoded(value(config, "access_token"));
        String payload = mapper.writeValueAsString(Map.of("msgtype", "m.text", "body", text));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IllegalStateException("Matrix returned HTTP " + response.statusCode() + ".");
    }
    private String base() { return base(config); }
    private static String base(Map<String, Object> config) { String value = value(config, "homeserver"); return value.endsWith("/") ? value : value + "/"; }
    private void pause() { try { Thread.sleep(5_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
