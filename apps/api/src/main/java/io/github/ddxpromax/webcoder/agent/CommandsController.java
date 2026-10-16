package io.github.ddxpromax.webcoder.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Majo control-command classification and /memory execution surface. */
@RestController @RequestMapping("/api/commands") @CrossOrigin(origins="*")
public class CommandsController {
    private final MemoryCommandService memory;
    public CommandsController(MemoryCommandService memory){this.memory=memory;}
    @PostMapping("/check") public Map<String,Object> check(@RequestBody Map<String,Object> body){String text=String.valueOf(body.getOrDefault("text","")).strip();boolean control=text.matches("(?i)^/(memory|stop|approve|deny)(?:\\s.*)?$");Map<String,Object> result=new LinkedHashMap<>();result.put("is_control_command",control);result.put("command_token",control?text.split("\\s+",2)[0]:null);return result;}
    @PostMapping("/run") public Map<String,Object> run(@RequestBody Map<String,Object> body){String text=String.valueOf(body.getOrDefault("text","")).strip();String agent=String.valueOf(body.getOrDefault("agent_id","default")).strip();String reply=text.toLowerCase().startsWith("/memory")?memory.execute(agent,text.length()>7?text.substring(7):""):"Only /memory commands are supported.";return Map.of("reply",reply);}
}
