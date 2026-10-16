package io.github.ddxpromax.webcoder.toolcalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory Majo lifecycle registry for caller-managed tool executions. */
@Component
public class ToolCallStore {
    public static final class Entry {
        public final String id,name,sessionId,agentId; public final long startedAt;
        public volatile String status="running",endState,output=""; public volatile Double deadline; public volatile boolean forceCancelled;
        Entry(String id,String name,String sessionId,String agentId,long startedAt){this.id=id;this.name=name;this.sessionId=sessionId;this.agentId=agentId;this.startedAt=startedAt;}
    }
    private final Map<String,Entry> entries=new ConcurrentHashMap<>();
    public Entry register(String id,String name,String sessionId,String agentId){Entry entry=new Entry(id,name,sessionId,agentId,System.currentTimeMillis());entries.put(id,entry);return entry;}
    public Entry get(String id){return entries.get(id);}
    public List<Entry> list(String sessionId){return entries.values().stream().filter(entry->sessionId==null||sessionId.equals(entry.sessionId)).sorted((a,b)->Long.compare(b.startedAt,a.startedAt)).toList();}
    public boolean cancel(String id,boolean force){Entry entry=get(id);if(entry==null||!"running".equals(entry.status))return false;entry.status="cancelled";entry.endState="cancelled";entry.forceCancelled=force;return true;}
    public boolean extendDeadline(String id,Double seconds,boolean noDeadline){Entry entry=get(id);if(entry==null||!"running".equals(entry.status))return false;if(noDeadline)entry.deadline=null;else if(seconds!=null&&seconds>0)entry.deadline=(double)(System.currentTimeMillis()+(long)(seconds*1000));else return false;return true;}
    public boolean offload(String id){Entry entry=get(id);if(entry==null||!"running".equals(entry.status))return false;entry.status="offloaded";entry.endState="offloaded";return true;}
    public void finish(String id,String endState,String output){Entry entry=get(id);if(entry==null)return;entry.status="done";entry.endState=endState;entry.output=output==null?"":output;}
    public Map<String,Object> info(Entry entry){Map<String,Object> result=new LinkedHashMap<>();result.put("tool_call_id",entry.id);result.put("tool_name",entry.name);result.put("session_id",entry.sessionId);result.put("agent_id",entry.agentId);result.put("status",entry.status);result.put("started_at",entry.startedAt);result.put("deadline",entry.deadline);result.put("elapsed",(System.currentTimeMillis()-entry.startedAt)/1000.0);result.put("extra",Map.of());result.put("end_state",entry.endState);result.put("force_cancelled",entry.forceCancelled);result.put("max_internal_timeout_secs",null);return result;}
}
