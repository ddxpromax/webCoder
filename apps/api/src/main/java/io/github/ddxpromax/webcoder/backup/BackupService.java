package io.github.ddxpromax.webcoder.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/** ZIP backup store and single active-job lifecycle, following Majo's backup API model. */
@Service
public class BackupService {
    private final MajoWorkingDirectory working; private final AgentStore agents; private final ObjectMapper mapper;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "majo-backup"); thread.setDaemon(true); return thread; });
    private final Map<String,Job> jobs = new ConcurrentHashMap<>(); private volatile String activeId;
    public BackupService(MajoWorkingDirectory working, AgentStore agents, ObjectMapper mapper) { this.working=working; this.agents=agents; this.mapper=mapper; }
    private Path directory() throws IOException { Path dir=working.resolve(".backups"); Files.createDirectories(dir); return dir; }
    public List<Map<String,Object>> list() throws IOException { List<Map<String,Object>> all=new ArrayList<>(); try(var files=Files.list(directory())) { for(Path zip:files.filter(p->p.getFileName().toString().endsWith(".zip")).toList()) { Map<String,Object> meta=meta(zip); if(meta!=null) all.add(publicMeta(meta)); } } all.sort((a,b)->String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at")))); return all; }
    public Map<String,Object> detail(String id) throws IOException { Path zip=find(id); if(zip==null) return null; return publicMeta(meta(zip)); }
    public Path export(String id) throws IOException { Path zip=find(id); if(zip==null) throw new IllegalArgumentException("Backup not found"); return zip; }
    public synchronized Map<String,Object> start(Map<String,Object> body) { if(activeId!=null && jobs.containsKey(activeId) && !jobs.get(activeId).terminal()) throw new IllegalStateException("A backup job is already running"); Job job=new Job(); jobs.put(job.id,job); activeId=job.id; worker.submit(()->create(job,body)); return job.view(); }
    public Map<String,Object> job(String id) { Job job=jobs.get(id); return job==null?null:job.view(); }
    public Map<String,Object> active() { return activeId==null?Map.of():job(activeId); }
    public Map<String,Object> cancel(String id) { Job job=jobs.get(id); if(job==null) return null; job.cancel=true; if(!job.terminal()) job.status="cancel_requested"; return job.view(); }
    public Map<String,Object> delete(List<String> ids) throws IOException { List<String> deleted=new ArrayList<>(); List<Map<String,String>> failed=new ArrayList<>(); for(String id:ids){Path path=find(id);if(path==null)failed.add(Map.of("id",id,"reason","not found"));else try{Files.delete(path);deleted.add(id);}catch(IOException e){failed.add(Map.of("id",id,"reason",e.getMessage()));}}return Map.of("deleted",deleted,"failed",failed);}
    public Map<String,Object> importZip(Path upload, boolean overwrite) throws IOException { Map<String,Object> meta=meta(upload);if(meta==null||!String.valueOf(meta.getOrDefault("id","")).matches("[A-Za-z0-9._-]{1,200}"))throw new IllegalArgumentException("Zip does not contain a valid meta.json");String id=String.valueOf(meta.get("id"));Path existing=find(id);if(existing!=null&&!overwrite)throw new Conflict(id);Files.move(upload,directory().resolve(id+".zip"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);return publicMeta(meta);}
    public Map<String,Object> restore(String id, List<String> ids) throws IOException { Path zip=find(id); if(zip==null) throw new IllegalArgumentException("Backup not found"); List<String> restored=new ArrayList<>(); try(ZipFile file=new ZipFile(zip.toFile())){var entries=file.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();String name=entry.getName();if(!name.startsWith("data/workspaces/"))continue;String[] parts=name.split("/",4);if(parts.length<4 || (!ids.isEmpty()&&!ids.contains(parts[2])))continue;Path base=agents.workspaceDirForAgent(parts[2]).toAbsolutePath().normalize();Path target=base.resolve(parts[3]).normalize();if(!target.startsWith(base))throw new IOException("Invalid backup path");Files.createDirectories(target.getParent());try(var in=file.getInputStream(entry)){Files.copy(in,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}restored.add(parts[2]+"/"+parts[3]);}}return Map.of("ok",true,"restored_paths",restored,"preserved_local_keys",List.of());}
    private void create(Job job, Map<String,Object> body) { try { job.phase="preparing"; Path target=directory().resolve(job.backupId+".zip"); Path temp=target.resolveSibling(target.getFileName()+".tmp"); Map<String,Object> scope=asMap(body.get("scope")); boolean includeAgents=bool(scope.get("include_agents"),true); List<String> selected=body.get("agents") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of(); List<Map<String,Object>> profiles=includeAgents?agents.listProfiles():List.of(); if(!selected.isEmpty()) profiles=profiles.stream().filter(p->selected.contains(String.valueOf(p.get("id")))).toList(); job.total=profiles.size(); Map<String,Object> meta=new LinkedHashMap<>(); meta.put("id",job.backupId);meta.put("name",String.valueOf(body.getOrDefault("name","")));meta.put("description",String.valueOf(body.getOrDefault("description","")));meta.put("created_at",Instant.now().toString());meta.put("version","1");meta.put("scope",Map.of("include_agents",includeAgents,"include_global_config",bool(scope.get("include_global_config"),true),"include_secrets",bool(scope.get("include_secrets"),false),"include_skill_pool",bool(scope.get("include_skill_pool"),true)));meta.put("agent_count",profiles.size());meta.put("majo_version","majo"); try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(temp))){for(Map<String,Object> profile:profiles){if(job.cancel)throw new Cancelled();String id=String.valueOf(profile.get("id"));job.current=id;zipDirectory(out,agents.workspaceDirForAgent(id),"data/workspaces/"+id+"/");job.index++;job.percent=10+(int)(75.0*job.index/Math.max(1,job.total));}if(bool(scope.get("include_global_config"),true)){Path config=working.resolve("agents.json");if(Files.isRegularFile(config))add(out,config,"data/config.json");}out.putNextEntry(new ZipEntry("meta.json"));out.write(mapper.writeValueAsBytes(meta));out.closeEntry();}Files.move(temp,target);job.result=publicMeta(meta);job.status="completed";job.phase="completed";job.percent=100;}catch(Cancelled e){job.status="cancelled";job.error="Backup cancelled";}catch(Exception e){job.status="failed";job.error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}finally{if(job.id.equals(activeId))activeId=null;}}
    private void zipDirectory(ZipOutputStream out,Path root,String prefix)throws IOException{if(!Files.isDirectory(root))return;try(var paths=Files.walk(root)){for(Path file:paths.filter(Files::isRegularFile).sorted().toList())add(out,file,prefix+root.relativize(file).toString().replace('\\','/'));}}
    private void add(ZipOutputStream out,Path file,String name)throws IOException{out.putNextEntry(new ZipEntry(name));Files.copy(file,out);out.closeEntry();}
    private Path find(String id)throws IOException{if(id==null||!id.matches("[A-Za-z0-9._-]{1,200}"))return null;Path direct=directory().resolve(id+".zip");if(Files.isRegularFile(direct))return direct;try(var files=Files.list(directory())){return files.filter(p->p.toString().endsWith(".zip")).filter(p->{try{return id.equals(String.valueOf(meta(p).get("id")));}catch(Exception e){return false;}}).findFirst().orElse(null);}}
    private Map<String,Object> meta(Path zip)throws IOException{try(ZipFile file=new ZipFile(zip.toFile())){ZipEntry entry=file.getEntry("meta.json");return entry==null?null:mapper.readValue(file.getInputStream(entry),new TypeReference<LinkedHashMap<String,Object>>(){});}}
    private Map<String,Object> publicMeta(Map<String,Object> meta){Map<String,Object> copy=new LinkedHashMap<>(meta);copy.remove("signature");return copy;}
    @SuppressWarnings("unchecked")private Map<String,Object> asMap(Object value){return value instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}private boolean bool(Object value,boolean fallback){return value==null?fallback:Boolean.TRUE.equals(value);}
    private static class Cancelled extends RuntimeException{} private static class Job { final String id="bkjob-"+UUID.randomUUID().toString().replace("-","").substring(0,16);final String backupId="majo-majo-"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID().toString().replace("-","").substring(0,8);volatile String status="running",phase="preparing",current="",error="";volatile int percent,index,total;volatile boolean cancel;volatile Map<String,Object> result;boolean terminal(){return status.equals("completed")||status.equals("failed")||status.equals("cancelled");}Map<String,Object>view(){Map<String,Object>m=new LinkedHashMap<>();m.put("job_id",id);m.put("backup_id",backupId);m.put("status",status);m.put("phase",phase);m.put("percent",percent);m.put("current_agent",current);m.put("agent_index",index);m.put("total_agents",total);m.put("result",result);m.put("error",error);return m;}}
    public static class Conflict extends IllegalArgumentException { public Conflict(String id){super(id);} }
}
