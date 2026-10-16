package io.github.ddxpromax.webcoder.compat;

import io.github.ddxpromax.webcoder.cron.CronService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Majo root compatibility paths retained for older desktop and channel clients. */
@RestController @CrossOrigin(origins="*")
public class RootCompatController {
    private final CronService cron; public RootCompatController(CronService cron){this.cron=cron;}
    @GetMapping("/crons/jobs") public List<Map<String,Object>> jobs(){return cron.list("default");}
    @PostMapping("/crons/jobs") public ResponseEntity<?> create(@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(cron.create("default",body));}catch(IllegalArgumentException error){return ResponseEntity.unprocessableEntity().body(Map.of("detail",error.getMessage()));}}
    @DeleteMapping("/crons/jobs/{id}") public ResponseEntity<?> delete(@PathVariable String id){return cron.delete("default",id)?ResponseEntity.ok(Map.of("deleted",true)):ResponseEntity.status(404).body(Map.of("detail","job not found"));}
    @PostMapping("/voice/incoming") public ResponseEntity<String> voiceIncoming(){return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say>Voice channel is not available.</Say></Response>");}
    @PostMapping("/voice/status-callback") public Map<String,String> voiceStatus(){return Map.of("status","ignored");}
}
