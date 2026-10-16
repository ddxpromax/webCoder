package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import io.github.ddxpromax.webcoder.channel.ChannelMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Majo OneBot v11 WebSocket client adapter for NapCat, GoCQHTTP, and Lagrange. */
@Component
public class OneBotChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(OneBotChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket socket;
    private volatile Thread connector;

    @Override public String id() { return "onebot"; }
    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        String host = value(config, "ws_host");
        if (host.isBlank()) throw new IllegalArgumentException("OneBot ws_host is required.");
        if (running.get()) return;
        this.config = Map.copyOf(config); this.dispatcher = dispatcher; running.set(true);
        connector = new Thread(() -> connectLoop(host, integer(config.get("ws_port"), 6700)), "onebot-channel-ws");
        connector.setDaemon(true); connector.start();
    }
    @Override public synchronized void stop() {
        running.set(false); if (connector != null) connector.interrupt(); connector = null;
        WebSocket current = socket; if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) { }
    }
    @Override public boolean isRunning() { return running.get(); }

    private void connectLoop(String host, int port) {
        while (running.get()) {
            try {
                String token = value(config, "access_token");
                String endpoint = "ws://" + host + ":" + port + (token.isBlank() ? "" : "?access_token=" + token);
                WebSocket connected = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(endpoint), new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();
                        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
                        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence chunk, boolean last) {
                            buffer.append(chunk);
                            if (last) { String payload = buffer.toString(); buffer.setLength(0); try { dispatch(mapper.readTree(payload)); } catch (Exception exception) { log.warn("Invalid OneBot event: {}", exception.getMessage()); } }
                            webSocket.request(1); return null;
                        }
                        @Override public void onError(WebSocket webSocket, Throwable error) { if (running.get()) log.warn("OneBot socket error: {}", error.getMessage()); }
                    }).join();
                socket = connected;
                while (running.get() && !connected.isInputClosed() && !connected.isOutputClosed()) Thread.sleep(1_000);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            catch (Exception exception) { if (running.get()) log.warn("OneBot connection failed: {}", exception.getMessage()); }
            pause();
        }
    }

    private void dispatch(JsonNode event) {
        if (!"message".equals(event.path("post_type").asText())) return;
        String sender = event.path("user_id").asText(), text = event.path("raw_message").asText();
        if (sender.isBlank() || text.isBlank()) return;
        boolean groupMessage = "group".equals(event.path("message_type").asText("private"));
        String group = groupMessage ? event.path("group_id").asText() : null;
        String name = event.path("sender").path("card").asText(event.path("sender").path("nickname").asText(sender));
        dispatcher.dispatch(config, new ChannelMessage(id(), sender, name, group, text, groupMessage ? group : sender),
            (recipient, reply) -> sendReply(recipient, reply, groupMessage));
    }

    private void sendReply(String recipient, String text, boolean group) {
        try {
            WebSocket current = socket;
            if (current == null || current.isOutputClosed()) { log.warn("OneBot is not connected; message was not sent."); return; }
            String target = group ? "group_id" : "user_id";
            current.sendText(mapper.writeValueAsString(Map.of("action", "send_msg", "params", Map.of(target, Long.parseLong(recipient), "message", text))), true).join();
        } catch (Exception exception) { log.warn("OneBot send failed: {}", exception.getMessage()); }
    }
    private void pause() { try { Thread.sleep(5_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
    private static int integer(Object value, int fallback) { try { return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value)); } catch (Exception exception) { return fallback; } }
    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
}
