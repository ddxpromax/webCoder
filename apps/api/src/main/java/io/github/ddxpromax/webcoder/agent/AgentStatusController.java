package io.github.ddxpromax.webcoder.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo-compatible runtime status for an individual agent profile. */
@RestController @RequestMapping("/api/agents/{agentId}/agent-status") @CrossOrigin(origins="*")
public class AgentStatusController {
    private final AgentStore agents; private final TaskTracker tasks;
    public AgentStatusController(AgentStore agents,TaskTracker tasks){this.agents=agents;this.tasks=tasks;}
    @GetMapping public ResponseEntity<?> status(@PathVariable String agentId){Map<String,Object> profile=agents.getProfile(agentId);if(profile==null)return ResponseEntity.status(404).body(Map.of("detail","Agent '"+agentId+"' not found"));if(Boolean.FALSE.equals(profile.get("enabled"))){Map<String,Object> result=new LinkedHashMap<>();result.put("status","disabled");result.put("running_task_count",0);result.put("last_run_at",null);result.put("last_finish_at",null);return ResponseEntity.ok(result);}return ResponseEntity.ok(tasks.getGlobalStatus());}
}
