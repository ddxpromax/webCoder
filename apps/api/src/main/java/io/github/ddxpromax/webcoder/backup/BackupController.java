package io.github.ddxpromax.webcoder.backup;

import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api") @CrossOrigin(origins="*")
public class BackupController {
    private final BackupService backups; public BackupController(BackupService backups){this.backups=backups;}
    @GetMapping("/backups") public ResponseEntity<?> list(){try{return ResponseEntity.ok(backups.list());}catch(Exception e){return error(e);}}
    @GetMapping("/backups/{id}") public ResponseEntity<?> detail(@PathVariable String id){try{Map<String,Object> item=backups.detail(id);return item==null?ResponseEntity.status(404).body(Map.of("detail","Backup not found")):ResponseEntity.ok(item);}catch(Exception e){return error(e);}}
    @PostMapping("/backups/delete") public ResponseEntity<?> delete(@RequestBody Map<String,Object> body){try{List<String> ids=body.get("ids") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();return ResponseEntity.ok(backups.delete(ids));}catch(Exception e){return error(e);}}
    @GetMapping("/backups/{id}/export") public ResponseEntity<?> export(@PathVariable String id){try{return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+id+".zip\"").body(new FileSystemResource(backups.export(id)));}catch(IllegalArgumentException e){return ResponseEntity.status(404).body(Map.of("detail","Backup not found"));}catch(Exception e){return error(e);}}
    @PostMapping("/backups/jobs") public ResponseEntity<?> start(@RequestBody Map<String,Object> body){try{return ResponseEntity.status(202).body(backups.start(body));}catch(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("detail",e.getMessage()));}}
    @GetMapping("/backups/jobs/active") public Map<String,Object> active(){return backups.active();}
    @GetMapping("/backups/jobs/{id}") public ResponseEntity<?> job(@PathVariable String id){Map<String,Object> job=backups.job(id);return job==null?ResponseEntity.status(404).body(Map.of("detail","Backup job not found")):ResponseEntity.ok(job);}
    @PostMapping("/backups/jobs/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable String id){Map<String,Object> job=backups.cancel(id);return job==null?ResponseEntity.status(404).body(Map.of("detail","Backup job not found")):ResponseEntity.ok(job);}
    @PostMapping("/backups/{id}/restore") public ResponseEntity<?> restore(@PathVariable String id,@RequestBody Map<String,Object> body){try{List<String> ids=body.get("agent_ids") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();return ResponseEntity.ok(backups.restore(id,ids));}catch(IllegalArgumentException e){return ResponseEntity.status(404).body(Map.of("detail",e.getMessage()));}catch(Exception e){return error(e);}}
    @PostMapping("/backups/import") public ResponseEntity<?> importZip(@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean overwrite)throws Exception{if(file==null||file.isEmpty())return ResponseEntity.badRequest().body(Map.of("detail","file is required"));java.nio.file.Path temp=java.nio.file.Files.createTempFile("majo-backup-", ".zip");try{file.transferTo(temp);return ResponseEntity.ok(backups.importZip(temp,overwrite));}catch(BackupService.Conflict e){return ResponseEntity.status(409).body(Map.of("detail","backup_conflict","existing",e.getMessage()));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("detail",e.getMessage()));}finally{java.nio.file.Files.deleteIfExists(temp);}}
    private ResponseEntity<Map<String,String>> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("detail",e.getMessage()==null?"Backup operation failed":e.getMessage()));}
}
