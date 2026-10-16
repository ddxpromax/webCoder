package io.github.ddxpromax.webcoder.loop;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo custom loop-mode catalog and persistent stop-gate definitions. */
@RestController @RequestMapping("/api/loops") @CrossOrigin(origins="*")
public class LoopsController {
    private final AgentStore agents; public LoopsController(AgentStore agents){this.agents=agents;}
    @GetMapping public List<Map<String,Object>> list(HttpServletRequest request){String agent=agent(request);List<Map<String,Object>> result=new ArrayList<>(List.of(info("default","","The standard guarded agent loop.","builtin"),info("goal","goal","Set a goal and work until it is done.","builtin"),info("mission","mission","Run a persistent multi-step mission.","builtin")));for(Map<String,Object> mode:custom(agent))result.add(info(text(mode.get("id")),text(mode.get("slash_command")),text(mode.get("description")),"custom",text(mode.get("name"))));return result;}
    @GetMapping("/gates/catalog") public List<Map<String,Object>> gates(){return List.of(gate("iteration","Iteration limit","limits",Map.of("max_iterations",Map.of("type","integer","default",40,"minimum",1,"maximum",500))),gate("doom_loop","Repetition protection","safety",Map.of("window_size",Map.of("type","integer","default",3,"minimum",2,"maximum",20))),gate("token_budget","Token budget","limits",Map.of("max_total_tokens",Map.of("type","integer","default",120000,"minimum",1))),gate("timeout","Loop time limit","limits",Map.of("max_seconds",Map.of("type","number","default",1800,"minimum",1,"maximum",86400))),gate("tool_call_budget","Tool-call budget","limits",Map.of("max_calls",Map.of("type","integer","default",30,"minimum",1,"maximum",10000))),gate("qualitative_rubric","Qualitative completion check","quality",Map.of("rubric",Map.of("type","string","default","Verify the task before stopping. Continue if work remains."))),gate("completion_rubric","Completion signal check","quality",Map.of("completion_signal",Map.of("type","string","default","COMPLETED"))));}
    @GetMapping("/custom") public List<Map<String,Object>> customList(HttpServletRequest request){return custom(agent(request));}
    @PostMapping("/custom") public ResponseEntity<?> create(@RequestBody Map<String,Object> mode,HttpServletRequest request){String id=text(mode.get("id"));if(id.isBlank())return ResponseEntity.badRequest().body(Map.of("detail","id is required"));List<Map<String,Object>> modes=custom(agent(request));if(modes.stream().anyMatch(item->id.equals(text(item.get("id")))))return ResponseEntity.status(409).body(Map.of("detail","Mode ID already exists"));modes.add(new LinkedHashMap<>(mode));save(agent(request),modes);return ResponseEntity.status(201).body(mode);}
    @PutMapping("/custom/{id}") public ResponseEntity<?> update(@PathVariable String id,@RequestBody Map<String,Object> mode,HttpServletRequest request){if(!id.equals(text(mode.get("id"))))return ResponseEntity.status(422).body(Map.of("detail","Mode ID cannot change"));List<Map<String,Object>> modes=custom(agent(request));int index=index(modes,id);if(index<0)return ResponseEntity.notFound().build();modes.set(index,new LinkedHashMap<>(mode));save(agent(request),modes);return ResponseEntity.ok(mode);}
    @DeleteMapping("/custom/{id}") public ResponseEntity<?> delete(@PathVariable String id,HttpServletRequest request){List<Map<String,Object>> modes=custom(agent(request));int index=index(modes,id);if(index<0)return ResponseEntity.notFound().build();modes.remove(index);save(agent(request),modes);return ResponseEntity.noContent().build();}
    @PostMapping("/custom/{id}/duplicate") public ResponseEntity<?> duplicate(@PathVariable String id,HttpServletRequest request){List<Map<String,Object>> modes=custom(agent(request));int index=index(modes,id);if(index<0)return ResponseEntity.notFound().build();Map<String,Object> copy=new LinkedHashMap<>(modes.get(index));copy.put("id",unique(text(copy.get("id"))+"-copy",modes,"id"));copy.put("name",unique(text(copy.get("name"))+" Copy",modes,"name"));copy.put("slash_command",unique(text(copy.get("slash_command"))+"-copy",modes,"slash_command"));modes.add(copy);save(agent(request),modes);return ResponseEntity.status(201).body(copy);}
    private List<Map<String,Object>> custom(String agent){Map<String,Object> running=agents.getRunningConfig(agent);Object loop=running.get("loop");Object modes=loop instanceof Map<?,?> map?map.get("custom_modes"):null;List<Map<String,Object>> result=new ArrayList<>();if(modes instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map)result.add(map(map));return result;}
    private void save(String agent,List<Map<String,Object>> modes){Map<String,Object> running=agents.getRunningConfig(agent);Map<String,Object> loop=running.get("loop") instanceof Map<?,?> map?map(map):new LinkedHashMap<>();loop.put("custom_modes",modes);running.put("loop",loop);agents.saveRunningConfig(agent,running,agents.getApprovalLevel(agent));}
    private String agent(HttpServletRequest request){String value=request.getHeader("X-Agent-Id");return value==null||value.isBlank()?"default":value;}
    private Map<String,Object> info(String id,String command,String description,String source){return info(id,command,description,source,id);}
    private Map<String,Object> info(String id,String command,String description,String source,String name){return Map.of("id",id,"name",name,"slash_command",command,"description",description,"source",source);}
    private Map<String,Object> gate(String type,String title,String category,Map<String,Object> properties){Map<String,Object> result=new LinkedHashMap<>();result.put("type",type);result.put("title",title);result.put("description",title);result.put("category",category);result.put("schema",Map.of("type","object","properties",properties));result.put("cost","none");result.put("exclusive_group",type.equals("qualitative_rubric")||type.equals("completion_rubric")?"completion_rubric":null);return result;}
    private int index(List<Map<String,Object>> modes,String id){for(int i=0;i<modes.size();i++)if(id.equals(text(modes.get(i).get("id"))))return i;return -1;}
    private String unique(String base,List<Map<String,Object>> modes,String field){String candidate=base;int suffix=1;while(contains(modes,field,candidate)){candidate=base+"-"+suffix++;}return candidate;}
    private boolean contains(List<Map<String,Object>> modes,String field,String value){return modes.stream().anyMatch(item->value.equals(text(item.get(field))));}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Map<?,?> value){return new LinkedHashMap<>((Map<String,Object>)value);}
    private String text(Object value){return value==null?"":String.valueOf(value);}
}
