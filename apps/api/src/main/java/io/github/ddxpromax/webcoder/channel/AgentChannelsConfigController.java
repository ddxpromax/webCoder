package io.github.ddxpromax.webcoder.channel;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo agent-scoped aliases for global channel configuration. */
@RestController
@RequestMapping("/api/agents/{agentId}/config/channels")
@CrossOrigin(origins = "*")
public class AgentChannelsConfigController {
    private final ChannelsConfigController channels;
    public AgentChannelsConfigController(ChannelsConfigController channels) { this.channels = channels; }
    @GetMapping public Map<String, Object> list(@PathVariable String agentId) { return channels.list(); }
    @PutMapping public Map<String, Object> updateAll(@PathVariable String agentId, @RequestBody Map<String, Object> body) { return channels.updateAll(body); }
    @GetMapping("/types") public List<String> types(@PathVariable String agentId) { return channels.types(); }
    @GetMapping("/schemas") public Map<String, Object> schemas(@PathVariable String agentId) { return channels.schemas(); }
    @GetMapping("/{name}") public ResponseEntity<?> get(@PathVariable String agentId, @PathVariable String name) { return channels.get(name); }
    @PutMapping("/{name}") public ResponseEntity<?> update(@PathVariable String agentId, @PathVariable String name, @RequestBody Map<String, Object> body) { return channels.update(name, body); }
    @GetMapping("/{name}/health") public ResponseEntity<?> health(@PathVariable String agentId, @PathVariable String name) { return channels.health(name); }
    @PostMapping("/{name}/restart") public ResponseEntity<?> restart(@PathVariable String agentId, @PathVariable String name) { return channels.restart(name); }
    @GetMapping("/{name}/qrcode") public ResponseEntity<?> qrcode(@PathVariable String agentId, @PathVariable String name) { return channels.qrcode(name); }
    @GetMapping("/{name}/qrcode/status") public ResponseEntity<?> qrcodeStatus(@PathVariable String agentId, @PathVariable String name) { return channels.qrcodeStatus(name); }
}
