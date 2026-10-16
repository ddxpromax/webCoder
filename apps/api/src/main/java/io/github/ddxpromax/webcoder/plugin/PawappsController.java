package io.github.ddxpromax.webcoder.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo app-type plugin discovery, metadata and static-asset API. */
@RestController @RequestMapping("/api/pawapps") @CrossOrigin(origins="*")
public class PawappsController {
    private final MajoWorkingDirectory working; private final ObjectMapper mapper;
    public PawappsController(MajoWorkingDirectory working,ObjectMapper mapper){this.working=working;this.mapper=mapper;}
    @GetMapping public Map<String,Object> list(){List<Map<String,Object>> apps=dirs().stream().map(this::info).toList();return Map.of("apps",apps,"total",apps.size());}
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable String id){Map<String,Object> app=find(id);return app==null?ResponseEntity.status(404).body(Map.of("detail","PawApp not found: "+id)):ResponseEntity.ok(app);}
    @DeleteMapping("/{id}") public ResponseEntity<?> uninstall(@PathVariable String id){Path target=directory(id);if(target==null)return ResponseEntity.badRequest().body(Map.of("detail","Invalid app id"));if(!Files.isDirectory(target))return ResponseEntity.status(404).body(Map.of("detail","PawApp not found: "+id));try(var paths=Files.walk(target)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);return ResponseEntity.ok(Map.of("id",id,"message","PawApp '"+id+"' uninstalled."));}catch(Exception error){return ResponseEntity.internalServerError().body(Map.of("detail","Uninstall failed: "+error.getMessage()));}}
    @GetMapping("/{id}/settings") public Map<String,Object> settings(@PathVariable String id){Map<String,Object> app=find(id);return Map.of("app_id",id,"settings",app==null?List.of():app.getOrDefault("settings",List.of()));}
    @GetMapping("/{id}/static/{filePath:.*}") public ResponseEntity<?> staticFile(@PathVariable String id,@PathVariable String filePath){Path app=directory(id);if(app==null)return ResponseEntity.badRequest().body(Map.of("detail","Invalid app id"));Path target=app.resolve(filePath).normalize();if(!target.startsWith(app)||!Files.isRegularFile(target))return ResponseEntity.status(404).body(Map.of("detail","File not found"));try{return ResponseEntity.ok(Map.of("content",Files.readString(target)));}catch(Exception error){return ResponseEntity.internalServerError().body(Map.of("detail",error.getMessage()));}}
    private List<Path> dirs(){Path root=plugins();if(!Files.isDirectory(root))return List.of();try(var paths=Files.list(root)){return paths.filter(Files::isDirectory).filter(this::isApp).sorted().toList();}catch(Exception ignored){return List.of();}}
    private boolean isApp(Path dir){Map<String,Object> manifest=manifest(dir);Map<String,Object> meta=map(manifest.get("meta"));Object pawapp=meta.get("pawapp");return pawapp instanceof Map<?,?>&&!((Map<?,?>)pawapp).isEmpty()||manifest.containsKey("entry_page")||"true".equals(String.valueOf(pawapp));}
    private Map<String,Object> find(String id){for(Path dir:dirs()){Map<String,Object> value=info(dir);if(id.equals(value.get("id"))||id.equals(dir.getFileName().toString()))return value;}return null;}
    private Map<String,Object> info(Path dir){Map<String,Object> manifest=manifest(dir),pawapp=map(map(manifest.get("meta")).get("pawapp"));Map<String,Object> result=new LinkedHashMap<>();result.put("id",text(manifest.getOrDefault("id",dir.getFileName().toString())));result.put("name",text(manifest.getOrDefault("name",dir.getFileName().toString())));result.put("version",text(manifest.getOrDefault("version","0.0.0")));result.put("description",text(manifest.get("description")));result.put("author",text(manifest.get("author")));result.put("category",text(pawapp.get("category")));result.put("icon",text(pawapp.get("icon")));result.put("entry_page",text(pawapp.get("entry_page")));result.put("launch_scope",text(pawapp.get("launch_scope")));result.put("status","installed");result.put("home_page",null);result.put("dir",dir.toAbsolutePath().toString());result.put("settings",pawapp.getOrDefault("settings",List.of()));result.put("permissions",pawapp.getOrDefault("permissions",Map.of()));result.put("backends",pawapp.getOrDefault("backends",Map.of()));return result;}
    private Map<String,Object> manifest(Path dir){Path file=dir.resolve("plugin.json");if(!Files.isRegularFile(file))return Map.of();try{return mapper.readValue(file.toFile(),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ignored){return Map.of();}}
    private Path plugins(){return working.resolve("plugins");}
    private Path directory(String id){if(id==null||id.isBlank()||id.contains("/")||id.contains("\\")||id.equals(".")||id.equals(".."))return null;Path root=plugins().toAbsolutePath().normalize();Path target=root.resolve(id).normalize();return target.startsWith(root)?target:null;}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){return value instanceof Map<?,?> map?new LinkedHashMap<>((Map<String,Object>)map):Map.of();}
    private String text(Object value){return value==null?"":String.valueOf(value);}
}
