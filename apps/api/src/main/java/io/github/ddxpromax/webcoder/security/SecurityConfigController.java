package io.github.ddxpromax.webcoder.security;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.ToolGuardService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo security policy configuration, persisted under agents.json/security. */
@RestController
@RequestMapping("/api/config/security")
@CrossOrigin(origins = "*")
public class SecurityConfigController {
    private final AgentStore agents;
    private final ToolGuardService toolGuard;
    public SecurityConfigController(AgentStore agents, ToolGuardService toolGuard) { this.agents=agents; this.toolGuard=toolGuard; }

    @GetMapping("/tool-guard") public Map<String,Object> toolGuard(){return merged(toolGuardDefaults(),section("tool_guard"));}
    @PutMapping("/tool-guard") public Map<String,Object> putToolGuard(@RequestBody Map<String,Object> body){return put("tool_guard",body);}
    @GetMapping("/tool-guard/builtin-rules") public List<Map<String,Object>> builtinRules(){return toolGuard.builtinRules();}
    @GetMapping("/sandbox") public Map<String,Object> sandbox(@RequestParam(required=false) Boolean enabled){boolean value=enabled!=null?enabled:bool(security().get("sandbox_enabled"),false);Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",value);result.put("effective",value);result.put("reason",null);return result;}
    @PutMapping("/sandbox") public Map<String,Object> putSandbox(@RequestBody Map<String,Object> body){boolean value=bool(body.get("enabled"),false);Map<String,Object> sec=security();sec.put("sandbox_enabled",value);save(sec);Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",value);result.put("effective",value);result.put("reason",null);return result;}
    @GetMapping("/file-guard") public Map<String,Object> fileGuard(){return merged(Map.of("enabled",true,"paths",List.of(),"allow_preview_outside_workspace",true),section("file_guard"));}
    @PutMapping("/file-guard") public Map<String,Object> putFileGuard(@RequestBody Map<String,Object> body){return put("file_guard",body);}
    @GetMapping("/doom-loop") public Map<String,Object> doomLoop(){return merged(Map.of("enabled",true,"warn_after",3,"stop_after",6),section("doom_loop"));}
    @PutMapping("/doom-loop") public Map<String,Object> putDoomLoop(@RequestBody Map<String,Object> body){return put("doom_loop",body);}
    @GetMapping("/skill-scanner") public Map<String,Object> scanner(){return merged(Map.of("mode","warn","timeout",30,"whitelist",List.of()),section("skill_scanner"));}
    @PutMapping("/skill-scanner") public Map<String,Object> putScanner(@RequestBody Map<String,Object> body){return put("skill_scanner",body);}
    @GetMapping("/skill-scanner/blocked-history") public List<Map<String,Object>> history(){return maps(security().get("skill_scanner_blocked_history"));}
    @DeleteMapping("/skill-scanner/blocked-history") public Map<String,Object> clearHistory(){Map<String,Object> sec=security();sec.remove("skill_scanner_blocked_history");save(sec);return Map.of("cleared",true);}
    @DeleteMapping("/skill-scanner/blocked-history/{index}") public Map<String,Object> removeHistory(@PathVariable int index){Map<String,Object> sec=security();List<Object> values=list(sec.get("skill_scanner_blocked_history"));if(index<0||index>=values.size())return Map.of("removed",false);values.remove(index);sec.put("skill_scanner_blocked_history",values);save(sec);return Map.of("removed",true);}
    @PostMapping("/skill-scanner/whitelist") public Map<String,Object> whitelist(@RequestBody Map<String,Object> body){Map<String,Object> sec=security(),scanner=section(sec,"skill_scanner");List<Object> whitelist=list(scanner.get("whitelist"));Map<String,Object> entry=new LinkedHashMap<>();entry.put("skill_name",text(body.get("skill_name")));entry.put("content_hash",text(body.get("content_hash")));entry.put("added_at",Instant.now().toString());whitelist.add(entry);scanner.put("whitelist",whitelist);sec.put("skill_scanner",scanner);save(sec);return Map.of("whitelisted",true,"skill_name",entry.get("skill_name"));}
    @DeleteMapping("/skill-scanner/whitelist/{skillName}") public Map<String,Object> removeWhitelist(@PathVariable String skillName){Map<String,Object> sec=security(),scanner=section(sec,"skill_scanner");List<Object> whitelist=list(scanner.get("whitelist"));whitelist.removeIf(item->item instanceof Map<?,?> map&&skillName.equals(text(map.get("skill_name"))));scanner.put("whitelist",whitelist);sec.put("skill_scanner",scanner);save(sec);return Map.of("removed",true,"skill_name",skillName);}
    @GetMapping("/allow-no-auth-hosts") public Map<String,Object> hosts(){Object value=security().get("allow_no_auth_hosts");return Map.of("hosts",value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of("127.0.0.1","::1"));}
    @PutMapping("/allow-no-auth-hosts") public Map<String,Object> putHosts(@RequestBody Map<String,Object> body){List<String> hosts=body.get("hosts") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();Map<String,Object> sec=security();sec.put("allow_no_auth_hosts",hosts);save(sec);return Map.of("hosts",hosts);}

    private Map<String,Object> put(String key,Map<String,Object> value){Map<String,Object> sec=security();sec.put(key,new LinkedHashMap<>(value));save(sec);return value;}
    private Map<String,Object> security(){return section(agents.loadConfig(),"security");}
    private Map<String,Object> section(String key){return section(security(),key);}
    @SuppressWarnings("unchecked") private Map<String,Object> section(Map<String,Object> parent,String key){Object value=parent.get(key);return value instanceof Map<?,?> map?new LinkedHashMap<>((Map<String,Object>)map):new LinkedHashMap<>();}
    private void save(Map<String,Object> security){agents.updateRoot("security",security);}
    private Map<String,Object> merged(Map<String,Object> defaults,Map<String,Object> saved){Map<String,Object> result=new LinkedHashMap<>(defaults);result.putAll(saved);return result;}
    private Map<String,Object> toolGuardDefaults(){Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",true);result.put("guarded_tools",null);result.put("denied_tools",List.of());result.put("auto_denied_rules",List.of("SAFETY_CHECKS_DESTRUCTIVE_COMMAND"));result.put("custom_rules",List.of());result.put("disabled_rules",List.of());result.put("shell_evasion_checks",Map.of("command_substitution",true,"environment_variable",true,"input_redirection",true,"null_byte",true,"quoting",true,"semantic_injection",true,"shell_metacharacters",true,"whitespace_variants",true));return result;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>> result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> map)result.add(new LinkedHashMap<>((Map<String,Object>)map));return result;}
    private List<Object> list(Object value){return value instanceof List<?> source?new ArrayList<>(source):new ArrayList<>();}
    private boolean bool(Object value,boolean fallback){return value==null?fallback:value instanceof Boolean flag?flag:Boolean.parseBoolean(String.valueOf(value));}
    private String text(Object value){return value==null?"":String.valueOf(value);}
}
