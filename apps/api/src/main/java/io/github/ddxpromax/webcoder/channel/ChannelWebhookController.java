package io.github.ddxpromax.webcoder.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.channel.impl.QQChannel;
import io.github.ddxpromax.webcoder.channel.impl.WeChatChannel;
import io.github.ddxpromax.webcoder.channel.impl.WeComCrypto;
import io.github.ddxpromax.webcoder.channel.impl.WecomChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo public callback endpoints for WeCom, WeChat relay, and QQ Official Bot. */
@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "*")
public class ChannelWebhookController {
    private final ObjectMapper mapper;
    private final AgentStore agents;
    private final ChannelRegistry registry;
    private final ChannelDispatcher dispatcher;
    private final WecomChannel wecom;
    private final WeChatChannel wechat;
    private final QQChannel qq;

    public ChannelWebhookController(ObjectMapper mapper, AgentStore agents, ChannelRegistry registry,
                                    ChannelDispatcher dispatcher, WecomChannel wecom, WeChatChannel wechat, QQChannel qq) {
        this.mapper = mapper; this.agents = agents; this.registry = registry; this.dispatcher = dispatcher;
        this.wecom = wecom; this.wechat = wechat; this.qq = qq;
    }

    @GetMapping(value = "/wecom/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyWecom(@RequestParam("msg_signature") String signature, @RequestParam String timestamp,
                                              @RequestParam String nonce, @RequestParam String echostr) {
        Map<String, Object> config = config("wecom");
        if (!enabled("wecom") || value(config, "callback_token").isBlank() || value(config, "callback_encoding_aes_key").isBlank()) return ResponseEntity.status(500).body("WeCom callback is disabled or incomplete.");
        if (!WeComCrypto.verifySignature(value(config, "callback_token"), timestamp, nonce, echostr, signature)) return ResponseEntity.status(403).body("Invalid signature.");
        try { return ResponseEntity.ok(WeComCrypto.decrypt(value(config, "callback_encoding_aes_key"), echostr)); }
        catch (RuntimeException exception) { return ResponseEntity.status(500).body("Unable to decrypt callback."); }
    }

    @PostMapping(value = "/wecom/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receiveWecom(@RequestParam("msg_signature") String signature, @RequestParam String timestamp,
                                               @RequestParam String nonce, @RequestBody String body) {
        if (!enabled("wecom")) return ResponseEntity.ok("success");
        Map<String, Object> config = config("wecom");
        try {
            String encrypted = xmlValue(body, "Encrypt");
            if (!WeComCrypto.verifySignature(value(config, "callback_token"), timestamp, nonce, encrypted, signature)) return ResponseEntity.status(403).body("Invalid signature.");
            String xml = WeComCrypto.decrypt(value(config, "callback_encoding_aes_key"), encrypted);
            String sender = xmlValue(xml, "FromUserName"), text = xmlValue(xml, "Content");
            if ("text".equals(xmlValue(xml, "MsgType")) && !sender.isBlank() && !text.isBlank()) dispatcher.dispatch(config, new ChannelMessage("wecom", sender, sender, null, text, sender), (to, reply) -> wecom.sendText(config, to, reply));
            return ResponseEntity.ok("success");
        } catch (RuntimeException exception) { return ResponseEntity.status(500).body("Unable to process callback."); }
    }

    @PostMapping(value = "/wechat/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receiveWechat(@RequestBody String raw) {
        if (!enabled("wechat")) return ResponseEntity.ok(Map.of("status", "ignored", "detail", "channel disabled"));
        Map<String, Object> config = config("wechat");
        try {
            JsonNode body = mapper.readTree(raw);
            if (!value(config, "bot_token").isBlank() && !value(config, "bot_token").equals(body.path("token").asText())) return ResponseEntity.status(403).body(Map.of("detail", "Invalid token."));
            String sender = body.path("sender").asText(), text = body.path("text").asText();
            if (sender.isBlank() || text.isBlank()) return ResponseEntity.ok(Map.of("status", "ignored"));
            String group = body.path("group").isMissingNode() || body.path("group").isNull() ? null : body.path("group").asText();
            dispatcher.dispatch(config, new ChannelMessage("wechat", sender, body.path("name").asText(sender), group, text, group == null ? sender : group), (to, reply) -> wechat.sendText(config, to, reply));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception exception) { return ResponseEntity.status(400).body(Map.of("detail", "Invalid webhook payload.")); }
    }

    @PostMapping(value = "/qq/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receiveQq(@RequestBody String raw) {
        if (!enabled("qq")) return ResponseEntity.ok(Map.of("status", "ignored", "detail", "channel disabled"));
        try {
            JsonNode event = mapper.readTree(raw).path("d"); String type = event.path("t").asText(); String text = qqText(event);
            if ("C2C_FRIEND".equals(type)) { String sender = event.path("author").path("user_openid").asText(); if (!sender.isBlank() && !text.isBlank()) dispatcher.dispatch(config("qq"), new ChannelMessage("qq", sender, sender, null, text, sender), (to, reply) -> qq.sendText(config("qq"), to, reply)); }
            if ("GROUP_AT_MESSAGE".equals(type)) { String group = event.path("group_openid").asText(), sender = event.path("author").path("member_openid").asText(); if (!group.isBlank() && !text.isBlank()) dispatcher.dispatch(config("qq"), new ChannelMessage("qq", sender, sender, group, text, group), (to, reply) -> qq.sendGroupText(config("qq"), to, reply)); }
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception exception) { return ResponseEntity.status(400).body(Map.of("detail", "Invalid QQ webhook payload.")); }
    }

    private boolean enabled(String id) { return registry.isRunning(id); }
    @SuppressWarnings("unchecked") private Map<String, Object> config(String id) { Object channels = agents.loadConfig().get("channels"); if (channels instanceof Map<?, ?> all && all.get(id) instanceof Map<?, ?> config) return new LinkedHashMap<>((Map<String, Object>) config); return new LinkedHashMap<>(); }
    private static String value(Map<String, Object> config, String key) { Object value = config.get(key); return value == null ? "" : String.valueOf(value); }
    private static String xmlValue(String xml, String tag) { String open = "<" + tag + "><![CDATA["; int start = xml.indexOf(open); if (start >= 0) { int end = xml.indexOf("]]></" + tag + ">", start + open.length()); return end < 0 ? "" : xml.substring(start + open.length(), end); } open = "<" + tag + ">"; start = xml.indexOf(open); int end = xml.indexOf("</" + tag + ">", start + open.length()); return start < 0 || end < 0 ? "" : xml.substring(start + open.length(), end); }
    private static String qqText(JsonNode event) { StringBuilder text = new StringBuilder(); for (JsonNode segment : event.path("content")) if ("text".equals(segment.path("type").asText())) text.append(segment.path("data").path("text").asText()); return text.toString(); }
}
