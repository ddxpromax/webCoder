package io.github.ddxpromax.webcoder.toolcalls;

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

/** Majo-compatible inspect/control API for registered tool calls. */
@RestController @RequestMapping("/api/tool-calls") @CrossOrigin(origins="*")
public class ToolCallsController {
    private final ToolCallStore store; public ToolCallsController(ToolCallStore store){this.store=store;}
    @GetMapping("/{sessionId}") public Map<String,Object> list(@PathVariable String sessionId){List<Map<String,Object>> items=store.list(sessionId).stream().map(store::info).toList();return Map.of("items",items,"total",items.size());}
    @GetMapping("/{sessionId}/{toolCallId}") public ResponseEntity<?> detail(@PathVariable String sessionId,@PathVariable String toolCallId){ToolCallStore.Entry entry=find(sessionId,toolCallId);return entry==null?missing(toolCallId):ResponseEntity.ok(store.info(entry));}
    @PostMapping("/{sessionId}/{toolCallId}/cancel") public ResponseEntity<?> cancel(@PathVariable String sessionId,@PathVariable String toolCallId,@RequestBody(required=false) Map<String,Object> body){if(find(sessionId,toolCallId)==null)return missing(toolCallId);if(!store.cancel(toolCallId,body!=null&&Boolean.TRUE.equals(body.get("force"))))return conflict("Cannot cancel");return accepted(toolCallId);}
    @PostMapping("/{sessionId}/{toolCallId}/extend-deadline") public ResponseEntity<?> deadline(@PathVariable String sessionId,@PathVariable String toolCallId,@RequestBody Map<String,Object> body){if(find(sessionId,toolCallId)==null)return missing(toolCallId);Object value=body.get("seconds");Double seconds=value instanceof Number number?number.doubleValue():null;if(!store.extendDeadline(toolCallId,seconds,Boolean.TRUE.equals(body.get("no_deadline"))))return conflict("Cannot extend deadline (capped or invalid)");return accepted(toolCallId);}
    @PostMapping("/{sessionId}/{toolCallId}/offload") public ResponseEntity<?> offload(@PathVariable String sessionId,@PathVariable String toolCallId){if(find(sessionId,toolCallId)==null)return missing(toolCallId);if(!store.offload(toolCallId))return conflict("Cannot offload (not running)");return accepted(toolCallId);}
    @GetMapping("/{sessionId}/{toolCallId}/output") public ResponseEntity<?> output(@PathVariable String sessionId,@PathVariable String toolCallId){ToolCallStore.Entry entry=find(sessionId,toolCallId);if(entry==null)return missing(toolCallId);Map<String,Object> result=new LinkedHashMap<>();result.put("tool_call_id",toolCallId);result.put("is_closed",!"running".equals(entry.status));result.put("final_state",entry.endState);result.put("content",entry.output.isBlank()?List.of():List.of(Map.of("type","text","text",entry.output)));return ResponseEntity.ok(result);}
    @GetMapping("/{sessionId}/{toolCallId}/stream") public ResponseEntity<?> stream(@PathVariable String sessionId,@PathVariable String toolCallId){ToolCallStore.Entry entry=find(sessionId,toolCallId);return entry==null?missing(toolCallId):ResponseEntity.ok(Map.of("status",entry.status,"tool_call_id",toolCallId));}
    private ToolCallStore.Entry find(String session,String id){ToolCallStore.Entry entry=store.get(id);return entry!=null&&session.equals(entry.sessionId)?entry:null;}
    private ResponseEntity<Map<String,String>> missing(String id){return ResponseEntity.status(404).body(Map.of("detail","Tool call not found: "+id));}
    private ResponseEntity<Map<String,String>> conflict(String message){return ResponseEntity.status(409).body(Map.of("detail",message));}
    private ResponseEntity<Map<String,String>> accepted(String id){return ResponseEntity.status(202).body(Map.of("status","accepted","tool_call_id",id));}
}
