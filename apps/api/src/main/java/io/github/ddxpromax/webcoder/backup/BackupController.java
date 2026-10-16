package io.github.ddxpromax.webcoder.backup;

import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    @GetMapping(value="/backups/jobs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public ResponseEntity<StreamingResponseBody> events(@PathVariable String id){if(backups.job(id)==null)return ResponseEntity.notFound().build();return ResponseEntity.ok(out->{while(true){Map<String,Object> job=backups.job(id);if(job==null)break;out.write(("data: "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(job)+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=String.valueOf(job.get("status"));if(status.equals("completed")||status.equals("failed")||status.equals("cancelled"))break;try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}});}
    @PostMapping("/backups/jobs/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable String id){Map<String,Object> job=backups.cancel(id);return job==null?ResponseEntity.status(404).body(Map.of("detail","Backup job not found")):ResponseEntity.ok(job);}
    @PostMapping("/backups/{id}/restore") public ResponseEntity<?> restore(@PathVariable String id,@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(backups.restore(id,body));}catch(IllegalArgumentException e){String message=e.getMessage();return ResponseEntity.status("Backup not found".equals(message)?404:400).body(Map.of("detail",message));}catch(Exception e){return error(e);}}
    @PostMapping("/backups/import") public ResponseEntity<?> importZip(@RequestParam(value="file",required=false) MultipartFile file,@RequestParam(value="pending_token",required=false) String pendingToken,@RequestParam(value="trust_mode",required=false) String trustMode)throws Exception{if(pendingToken!=null&&!pendingToken.isBlank()){try{return ResponseEntity.ok(backups.resolvePending(pendingToken));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("detail",e.getMessage()));}}if(file==null||file.isEmpty())return ResponseEntity.badRequest().body(Map.of("detail","file is required"));java.nio.file.Path temp=backups.createUploadTemp(trustMode);boolean keep=false;try{file.transferTo(temp);return ResponseEntity.ok(backups.importZip(temp,false,trustMode));}catch(BackupService.Conflict e){keep=true;Map<String,Object> conflict=new java.util.LinkedHashMap<>();conflict.put("detail","backup_conflict");conflict.put("existing",backups.detail(e.getMessage()));conflict.put("pending_token",temp.getFileName().toString());return ResponseEntity.status(409).body(conflict);}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("detail",e.getMessage()));}finally{if(!keep)java.nio.file.Files.deleteIfExists(temp);}}
    private ResponseEntity<Map<String,String>> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("detail",e.getMessage()==null?"Backup operation failed":e.getMessage()));}
}
