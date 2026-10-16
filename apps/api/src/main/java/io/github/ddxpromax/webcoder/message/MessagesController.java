package io.github.ddxpromax.webcoder.message;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.inbox.InboxService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo outbound-message API; console messages become durable inbox events. */
@RestController @RequestMapping("/api/messages") @CrossOrigin(origins="*")
public class MessagesController {
    private final InboxService inbox;private final AgentStore agents;public MessagesController(InboxService inbox,AgentStore agents){this.inbox=inbox;this.agents=agents;}
    @PostMapping("/send") public ResponseEntity<?> send(@RequestBody Map<String,Object> body,@RequestHeader(value="X-Agent-Id",required=false) String headerAgent){String channel=text(body.get("channel")),message=text(body.get("text")),agent=headerAgent==null||headerAgent.isBlank()?"default":headerAgent;if(channel.isBlank()||message.isBlank())return ResponseEntity.badRequest().body(Map.of("detail","channel and text are required"));if(agents.getProfile(agent)==null)return ResponseEntity.status(404).body(Map.of("detail","Agent not found: "+agent));if(!"console".equals(channel))return ResponseEntity.status(404).body(Map.of("detail","Channel not found: "+channel));String user=text(body.get("target_user")),session=text(body.get("target_session"));Map<String,Object> payload=new LinkedHashMap<>();payload.put("_api_send",true);payload.put("agent_id",agent);payload.put("channel",channel);payload.put("target_user",user);payload.put("target_session",session);inbox.appendEvent(agent,"console",session,"message","success",message.length()>80?message.substring(0,80):message,message,"info",payload);return ResponseEntity.ok(Map.of("success",true,"message","Message sent successfully to console"));}
    private String text(Object value){return value==null?"":String.valueOf(value);}
}
