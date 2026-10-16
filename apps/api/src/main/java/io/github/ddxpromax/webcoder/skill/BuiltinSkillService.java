package io.github.ddxpromax.webcoder.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** English packaged Majo skill candidates for the shared Pool. */
@Service
public class BuiltinSkillService {
    private final SkillPoolService pool;
    public BuiltinSkillService(SkillPoolService pool) { this.pool = pool; }
    public List<Map<String,Object>> candidates() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String name:names()) result.add(Map.of("skill_name",name,"language","en","available_languages",List.of("en")));
        return result;
    }
    public Map<String,Object> importSkills(List<String> names, boolean overwrite) {
        List<String> imported=new ArrayList<>(), conflicts=new ArrayList<>();
        for(String name:names){try{String content=read(name);Map<String,Object> result=pool.create(name,content,null,null,null,"builtin");if(Boolean.TRUE.equals(result.get("success")))imported.add(name);else if(overwrite){pool.save(name,name,content,null,true);imported.add(name);}else conflicts.add(name);}catch(Exception e){conflicts.add(name);}}
        return Map.of("imported",imported,"conflicts",conflicts);
    }
    private List<String> names(){try{String index=new String(new ClassPathResource("builtin-skills/index.txt").getInputStream().readAllBytes(),StandardCharsets.UTF_8);return index.lines().filter(line->!line.isBlank()).toList();}catch(IOException e){throw new IllegalStateException("Could not read packaged skill index",e);}}
    private String read(String name)throws IOException{return new String(new ClassPathResource("builtin-skills/"+name+"/SKILL.md").getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
}
