package io.github.ddxpromax.webcoder.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.channel.Channel;
import io.github.ddxpromax.webcoder.channel.ChannelDispatcher;
import io.github.ddxpromax.webcoder.channel.ChannelMessage;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Telegram Bot API adapter using Majo's long-polling delivery model. */
@Component
public class TelegramChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong offset = new AtomicLong();
    private volatile Thread poller;
    private volatile HttpClient client;
    private volatile Map<String, Object> config = Map.of();
    private volatile ChannelDispatcher dispatcher;

    @Override public String id() { return "telegram"; }

    @Override public synchronized void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (value(config, "bot_token").isBlank()) throw new IllegalArgumentException("Telegram bot_token is required.");
        if (running.get()) return;
        this.config = Map.copyOf(config);
        this.dispatcher = dispatcher;
        this.client = buildClient(config);
        running.set(true);
        poller = new Thread(this::poll, "telegram-channel-poll");
        poller.setDaemon(true);
        poller.start();
    }

    @Override public synchronized void stop() {
        running.set(false);
        if (poller != null) poller.interrupt();
        poller = null;
    }

    @Override public boolean isRunning() { return running.get(); }

    @Override public String sendText(Map<String, Object> config, String recipient, String text) {
        try {
            String recipientId = recipient == null ? "" : recipient.strip();
            if (recipientId.isBlank()) return "Telegram recipient is required.";
            String payload = mapper.writeValueAsString(Map.of("chat_id", recipientId, "text", text == null ? "" : text));
            HttpRequest request = HttpRequest.newBuilder(endpoint(config, "sendMessage"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = clientFor(config).send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 400 ? null : "Telegram API returned HTTP " + response.statusCode() + ".";
        } catch (Exception exception) {
            return message(exception);
        }
    }

    private void poll() {
        while (running.get()) {
            try {
                URI uri = URI.create(endpoint(config, "getUpdates").toString() + "?timeout=25&offset=" + offset.get());
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) { pause(); continue; }
                JsonNode root = mapper.readTree(response.body());
                if (!root.path("ok").asBoolean()) { pause(); continue; }
                for (JsonNode update : root.path("result")) {
                    offset.set(update.path("update_id").asLong() + 1);
                    dispatch(update);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                if (running.get()) log.warn("Telegram polling failed: {}", message(exception));
                pause();
            }
        }
    }

    private void dispatch(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || message.path("from").path("is_bot").asBoolean()) return;
        String sender = message.path("from").path("id").asText();
        String text = message.path("text").asText();
        String chatId = message.path("chat").path("id").asText();
        if (sender.isBlank() || chatId.isBlank() || text.isBlank()) return;
        String chatType = message.path("chat").path("type").asText("private");
        String group = "private".equals(chatType) ? null : chatId;
        String name = message.path("from").path("first_name").asText(sender);
        dispatcher.dispatch(config, new ChannelMessage(id(), sender, name, group, text, chatId),
            (recipient, reply) -> { String error = sendText(config, recipient, reply); if (error != null) log.warn("Telegram reply failed: {}", error); });
    }

    private void pause() {
        try { Thread.sleep(3_000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private HttpClient clientFor(Map<String, Object> candidate) {
        return candidate == config && client != null ? client : buildClient(candidate);
    }

    private static URI endpoint(Map<String, Object> config, String operation) {
        String base = value(config, "base_url");
        if (base.isBlank()) base = "https://api.telegram.org";
        if (!base.endsWith("/")) base += "/";
        return URI.create(base + "bot" + value(config, "bot_token") + "/" + operation);
    }

    private static HttpClient buildClient(Map<String, Object> config) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        String proxy = value(config, "http_proxy");
        if (!proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 8080 : uri.getPort())));
            } catch (Exception exception) { log.warn("Ignoring invalid Telegram proxy: {}", message(exception)); }
        }
        return builder.build();
    }

    private static String value(Map<String, Object> config, String key) { Object value = config == null ? null : config.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private static String message(Exception exception) { return exception.getMessage() == null ? "unexpected error" : exception.getMessage(); }
}
