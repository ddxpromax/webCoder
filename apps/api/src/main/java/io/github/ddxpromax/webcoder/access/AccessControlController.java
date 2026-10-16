package io.github.ddxpromax.webcoder.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo-compatible REST surface for external-channel access approvals. */
@RestController
@RequestMapping("/api/access-control")
@CrossOrigin(origins = "*")
public class AccessControlController {
    private final AccessControlStore store;
    public AccessControlController(AccessControlStore store) { this.store = store; }
    @GetMapping public Map<String, Object> list() { return store.getAll(); }
    @GetMapping("/pending/all") public List<Map<String, Object>> pending() { return store.allPending(); }
    @GetMapping("/{channel}") public Map<String, Object> channel(@PathVariable String channel) { return store.getAcl(channel); }
    @PostMapping("/pending/approve") public Map<String, Object> approve(@RequestBody Map<String, Object> body) { return apply(body, store::approvePending); }
    @PostMapping("/pending/deny") public Map<String, Object> deny(@RequestBody Map<String, Object> body) { return apply(body, store::denyPending); }
    @PostMapping("/pending/dismiss") public Map<String, Object> dismiss(@RequestBody Map<String, Object> body) { int count=0; for(Map<String,Object> entry:entries(body)) if(store.dismissPending(text(entry.get("channel")),text(entry.get("user_id")))) count++; return Map.of("status","ok","count",count); }
    @PostMapping("/pending/remark") public ResponseEntity<?> pendingRemark(@RequestBody Map<String, Object> body) { return found(store.updatePendingRemark(text(body.get("channel")),text(body.get("user_id")),text(body.get("remark")))); }
    @PostMapping("/whitelist/add") public Map<String, Object> whitelistAdd(@RequestBody Map<String, Object> body) { return add(body, true); }
    @PostMapping("/whitelist/remove") public Map<String, Object> whitelistRemove(@RequestBody Map<String, Object> body) { for(Map<String,Object> entry:entries(body)) store.removeFromWhitelist(text(entry.get("channel")),text(entry.get("user_id"))); return Map.of("status","ok","count",entries(body).size()); }
    @PostMapping("/blacklist/add") public Map<String, Object> blacklistAdd(@RequestBody Map<String, Object> body) { return add(body, false); }
    @PostMapping("/blacklist/remove") public Map<String, Object> blacklistRemove(@RequestBody Map<String, Object> body) { for(Map<String,Object> entry:entries(body)) store.removeFromBlacklist(text(entry.get("channel")),text(entry.get("user_id"))); return Map.of("status","ok","count",entries(body).size()); }
    @PostMapping("/remark") public ResponseEntity<?> remark(@RequestBody Map<String, Object> body) { return found(store.updateRemark(text(body.get("channel")),text(body.get("user_id")),text(body.get("remark")))); }
    @PostMapping("/username") public ResponseEntity<?> username(@RequestBody Map<String, Object> body) { return found(store.updateUsername(text(body.get("channel")),text(body.get("user_id")),text(body.get("username")))); }
    private Map<String,Object> add(Map<String,Object> body,boolean whitelist){for(Map<String,Object> entry:entries(body)){if(whitelist)store.addToWhitelist(text(entry.get("channel")),text(entry.get("user_id")),text(entry.get("remark")),text(entry.get("username")));else store.addToBlacklist(text(entry.get("channel")),text(entry.get("user_id")),text(entry.get("remark")),text(entry.get("username")));}return Map.of("status","ok","count",entries(body).size());}
    private Map<String,Object> apply(Map<String,Object> body,Decision decision){int count=0;for(Map<String,Object> entry:entries(body))if(decision.apply(text(entry.get("channel")),text(entry.get("user_id")),text(entry.get("remark"))))count++;return Map.of("status","ok","count",count);}
    private ResponseEntity<?> found(boolean found){return found?ResponseEntity.ok(Map.of("status","ok")):ResponseEntity.status(404).body(Map.of("detail","Entry not found"));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> entries(Map<String,Object> body){Object value=body.get("entries");if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>> result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> map)result.add(new LinkedHashMap<>((Map<String,Object>)map));return result;}
    private String text(Object value){return value==null?"":String.valueOf(value);}
    @FunctionalInterface private interface Decision { boolean apply(String channel,String userId,String remark); }
}
