package io.github.ddxpromax.webcoder.channel;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo channel configuration contract; channel credentials remain globally configured. */
@RestController
@RequestMapping("/api/config/channels")
@CrossOrigin(origins = "*")
public class ChannelsConfigController {
    private static final List<String> TYPES = List.of("console", "imessage", "discord", "dingtalk", "feishu", "qq", "telegram", "slack", "mqtt", "matrix", "mattermost", "wecom", "wechat", "voice", "sip", "xiaoyi", "yuanbao", "onebot");
    private final AgentStore agents;
    private final ChannelRegistry registry;
    @Autowired public ChannelsConfigController(AgentStore agents, ChannelRegistry registry) { this.agents = agents; this.registry = registry; }
    /** Kept for small focused unit tests that only exercise persistent configuration. */
    public ChannelsConfigController(AgentStore agents) { this(agents, null); }

    @GetMapping("/types") public List<String> types() { return ids(); }
    @GetMapping public Map<String, Object> list() { Map<String, Object> result = new LinkedHashMap<>(); Map<String, Object> stored = stored(); for (String id : ids()) { Map<String, Object> value = map(stored.get(id)); value.putIfAbsent("enabled", "console".equals(id)); value.putIfAbsent("bot_prefix", ""); result.put(id, value); } return result; }
    @GetMapping("/schemas") public Map<String, Object> schemas() { Map<String, Object> result = new LinkedHashMap<>(); for (String id : ids()) result.put(id, schema(id)); return result; }
    @PutMapping public Map<String, Object> updateAll(@RequestBody Map<String, Object> body) { Map<String, Object> next = new LinkedHashMap<>(); for (String id : ids()) if (body.get(id) instanceof Map<?, ?>) next.put(id, map(body.get(id))); agents.updateRoot("channels", next); reload(next); return list(); }
    @GetMapping("/{name}") public ResponseEntity<?> get(@PathVariable String name) { if (!known(name)) return unknown(name); Map<String, Object> result = map(stored().get(name)); result.putIfAbsent("enabled", false); result.putIfAbsent("bot_prefix", ""); return ResponseEntity.ok(result); }
    @PutMapping("/{name}") public ResponseEntity<?> update(@PathVariable String name, @RequestBody Map<String, Object> body) { if (!known(name)) return unknown(name); Map<String, Object> all = stored(); Map<String, Object> next = new LinkedHashMap<>(body); next.putIfAbsent("enabled", false); next.putIfAbsent("bot_prefix", ""); all.put(name, next); agents.updateRoot("channels", all); reload(all); return ResponseEntity.ok(next); }
    @GetMapping("/{name}/health") public ResponseEntity<?> health(@PathVariable String name) { if (!known(name)) return unknown(name); String detail = registry == null ? "" : registry.lastError(name); boolean healthy = registry == null ? "console".equals(name) : registry.isRunning(name); return ResponseEntity.ok(Map.of("channel", name, "status", healthy ? "healthy" : "stopped", "detail", detail)); }
    @PostMapping("/{name}/restart") public ResponseEntity<?> restart(@PathVariable String name) { if (!known(name)) return unknown(name); if (registry != null) registry.restart(name, map(stored().get(name))); return ResponseEntity.ok(Map.of("channel", name, "status", "restarted", "detail", registry == null ? "" : registry.lastError(name))); }
    @GetMapping("/{name}/qrcode") public ResponseEntity<?> qrcode(@PathVariable String name) { return unavailable(name); }
    @GetMapping("/{name}/qrcode/status") public ResponseEntity<?> qrcodeStatus(@PathVariable String name) { return unavailable(name); }

    private Map<String, Object> stored() { return map(agents.loadConfig().get("channels")); }
    private List<String> ids() { return registry == null ? TYPES : registry.channelIds(); }
    private boolean known(String name) { return registry == null ? TYPES.contains(name) : registry.contains(name); }
    private void reload(Map<String, Object> config) { if (registry != null) registry.reload(config); }
    private Map<String, Object> schema(String id) { Map<String, Object> result = new LinkedHashMap<>(); result.put("label", id.equals("console") ? "Console" : id); result.put("description", id.equals("console") ? "Web console channel (always available)" : "Configure the " + id + " channel."); result.put("plugin_id", "builtin"); List<Map<String, Object>> fields = new ArrayList<>(); fields.add(field("enabled", "Enabled", "switch")); fields.add(field("bot_prefix", "Bot prefix", "text")); fields.add(field("show_tool_calls", "Show tool calls", "switch")); fields.add(field("show_tool_results", "Show tool results", "switch")); fields.add(field("show_thinking", "Show reasoning", "switch")); if (!"console".equals(id)) { fields.add(select("dm_policy", "Direct-message policy", "open", "allowlist")); fields.add(select("group_policy", "Group policy", "open", "allowlist")); fields.add(field("allow_from", "Allowed user IDs", "text")); fields.add(field("require_mention", "Require mention in groups", "switch")); fields.addAll(platformFields(id)); } result.put("config_fields", fields); return result; }
    private List<Map<String, Object>> platformFields(String id) { return switch (id) {
        case "imessage" -> List.of(field("db_path", "Database path", "text"), field("poll_sec", "Polling interval (seconds)", "number"));
        case "discord" -> List.of(field("bot_token", "Bot token", "password"), field("http_proxy", "HTTP proxy", "text"), field("http_proxy_auth", "Proxy authentication", "password"));
        case "dingtalk" -> List.of(field("client_id", "Client ID", "text"), field("client_secret", "Client secret", "password"), field("robot_code", "Robot code", "text"), select("message_type", "Message type", "text", "markdown", "card"), field("cron_message_type", "Scheduled message type", "text"), field("at_sender_on_reply", "Mention sender in replies", "switch"));
        case "feishu" -> List.of(field("app_id", "App ID", "text"), field("app_secret", "App secret", "password"), field("encrypt_key", "Encryption key", "password"), field("verification_token", "Verification token", "password"), select("domain", "Platform", "feishu", "lark"));
        case "qq" -> List.of(field("app_id", "App ID", "text"), field("client_secret", "Client secret", "password"), field("ack_message", "ACK message", "text"), field("user_openid", "User OpenID", "text"));
        case "telegram" -> List.of(field("bot_token", "Bot token", "password"), field("base_url", "API base URL", "text"), field("http_proxy", "HTTP proxy", "text"), field("http_proxy_auth", "Proxy authentication", "password"), field("show_typing", "Show typing", "switch"));
        case "slack" -> List.of(field("bot_token", "Bot token", "password"), field("app_token", "App-level token", "password"), field("proxy", "Proxy", "text"));
        case "mqtt" -> List.of(field("host", "Host", "text"), field("port", "Port", "number"), select("transport", "Transport", "tcp", "ssl", "ws"), field("username", "Username", "text"), field("password", "Password", "password"), field("subscribe_topic", "Subscribe topic", "text"), field("publish_topic", "Publish topic", "text"), field("qos", "QoS", "number"));
        case "matrix" -> List.of(field("homeserver", "Homeserver", "text"), field("user_id", "User ID", "text"), field("access_token", "Access token", "password"));
        case "mattermost" -> List.of(field("url", "Server URL", "text"), field("bot_token", "Bot token", "password"));
        case "wecom" -> List.of(field("bot_id", "Bot ID", "text"), field("secret", "Secret", "password"), field("webhook_url", "Outbound webhook URL", "password"), field("callback_token", "Callback token", "password"), field("callback_encoding_aes_key", "Callback EncodingAESKey", "password"), field("welcome_text", "Welcome message", "text"));
        case "wechat" -> List.of(field("base_url", "Relay service URL", "text"), field("bot_token", "Bot token", "password"));
        case "voice" -> List.of(field("twilio_account_sid", "Account SID", "text"), field("twilio_auth_token", "Auth token", "password"), field("phone_number", "Phone number", "text"));
        case "sip" -> List.of(field("sip_server", "SIP server", "text"), field("sip_username", "SIP username", "text"), field("sip_password", "SIP password", "password"));
        case "xiaoyi" -> List.of(field("ak", "Access key", "text"), field("sk", "Secret key", "password"), field("agent_id", "Agent ID", "text"));
        case "yuanbao" -> List.of(field("app_id", "App ID", "text"), field("app_secret", "App secret", "password"), field("api_domain", "API domain", "text"));
        case "onebot" -> List.of(field("ws_host", "WebSocket host", "text"), field("ws_port", "WebSocket port", "number"), field("access_token", "Access token", "password"));
        default -> List.of();
    }; }
    private Map<String, Object> field(String name, String label, String type) { Map<String, Object> value = new LinkedHashMap<>(); value.put("name", name); value.put("label", label); value.put("type", type); if ("select".equals(type)) value.put("options", List.of("open", "allowlist")); return value; }
    private Map<String, Object> select(String name, String label, String... options) { Map<String, Object> value = field(name, label, "select"); value.put("options", List.of(options)); return value; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>(); }
    private ResponseEntity<?> unknown(String name) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Unknown channel: " + name)); }
    private ResponseEntity<?> unavailable(String name) { return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", "channel '" + name + "' does not support QR login in majo")); }
}
