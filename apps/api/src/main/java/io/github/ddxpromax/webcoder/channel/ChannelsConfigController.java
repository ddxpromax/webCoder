package io.github.ddxpromax.webcoder.channel;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public ChannelsConfigController(AgentStore agents) { this.agents = agents; }

    @GetMapping("/types") public List<String> types() { return TYPES; }
    @GetMapping public Map<String, Object> list() { Map<String, Object> result = new LinkedHashMap<>(); Map<String, Object> stored = stored(); for (String id : TYPES) { Map<String, Object> value = map(stored.get(id)); value.putIfAbsent("enabled", "console".equals(id)); value.putIfAbsent("bot_prefix", ""); result.put(id, value); } return result; }
    @GetMapping("/schemas") public Map<String, Object> schemas() { Map<String, Object> result = new LinkedHashMap<>(); for (String id : TYPES) result.put(id, schema(id)); return result; }
    @PutMapping public Map<String, Object> updateAll(@RequestBody Map<String, Object> body) { Map<String, Object> next = new LinkedHashMap<>(); for (String id : TYPES) if (body.get(id) instanceof Map<?, ?>) next.put(id, map(body.get(id))); agents.updateRoot("channels", next); return list(); }
    @GetMapping("/{name}") public ResponseEntity<?> get(@PathVariable String name) { if (!TYPES.contains(name)) return unknown(name); Map<String, Object> result = map(stored().get(name)); result.putIfAbsent("enabled", false); result.putIfAbsent("bot_prefix", ""); return ResponseEntity.ok(result); }
    @PutMapping("/{name}") public ResponseEntity<?> update(@PathVariable String name, @RequestBody Map<String, Object> body) { if (!TYPES.contains(name)) return unknown(name); Map<String, Object> all = stored(); Map<String, Object> next = new LinkedHashMap<>(body); next.putIfAbsent("enabled", false); next.putIfAbsent("bot_prefix", ""); all.put(name, next); agents.updateRoot("channels", all); return ResponseEntity.ok(next); }
    @GetMapping("/{name}/health") public ResponseEntity<?> health(@PathVariable String name) { if (!TYPES.contains(name)) return unknown(name); return ResponseEntity.ok(Map.of("channel", name, "status", "console".equals(name) ? "healthy" : "stopped", "detail", "")); }
    @PostMapping("/{name}/restart") public ResponseEntity<?> restart(@PathVariable String name) { if (!TYPES.contains(name)) return unknown(name); return ResponseEntity.ok(Map.of("channel", name, "status", "restarted", "detail", "")); }
    @GetMapping("/{name}/qrcode") public ResponseEntity<?> qrcode(@PathVariable String name) { return unavailable(name); }
    @GetMapping("/{name}/qrcode/status") public ResponseEntity<?> qrcodeStatus(@PathVariable String name) { return unavailable(name); }

    private Map<String, Object> stored() { return map(agents.loadConfig().get("channels")); }
    private Map<String, Object> schema(String id) { Map<String, Object> result = new LinkedHashMap<>(); result.put("label", id.equals("console") ? "Console" : id); result.put("description", id.equals("console") ? "Web console channel (always available)" : "Configure the " + id + " channel."); result.put("plugin_id", "builtin"); List<Map<String, Object>> fields = new ArrayList<>(); fields.add(field("enabled", "Enabled", "switch")); fields.add(field("bot_prefix", "Bot prefix", "text")); fields.add(field("show_tool_calls", "Show tool calls", "switch")); fields.add(field("show_tool_results", "Show tool results", "switch")); fields.add(field("show_thinking", "Show reasoning", "switch")); if (!"console".equals(id)) { fields.add(field("dm_policy", "Direct-message policy", "select")); fields.add(field("group_policy", "Group policy", "select")); fields.add(field("allow_from", "Allowed user IDs", "text")); } result.put("config_fields", fields); return result; }
    private Map<String, Object> field(String name, String label, String type) { Map<String, Object> value = new LinkedHashMap<>(); value.put("name", name); value.put("label", label); value.put("type", type); if ("select".equals(type)) value.put("options", List.of("open", "allowlist")); return value; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>(); }
    private ResponseEntity<?> unknown(String name) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Unknown channel: " + name)); }
    private ResponseEntity<?> unavailable(String name) { return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", "channel '" + name + "' does not support QR login in majo")); }
}
