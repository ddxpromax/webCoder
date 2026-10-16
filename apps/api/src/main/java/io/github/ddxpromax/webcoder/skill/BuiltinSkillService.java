package io.github.ddxpromax.webcoder.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
        for(String name:names){try{Bundle bundle=read(name);Map<String,Object> result=pool.create(name,bundle.content(),bundle.references(),bundle.scripts(),bundle.extra(),null,"builtin");if(Boolean.TRUE.equals(result.get("success")))imported.add(name);else if(overwrite&&Boolean.TRUE.equals(pool.replace(name,bundle.content(),bundle.references(),bundle.scripts(),bundle.extra(),"builtin").get("success")))imported.add(name);else conflicts.add(name);}catch(Exception e){conflicts.add(name);}}
        return Map.of("imported",imported,"conflicts",conflicts);
    }
    public Map<String,Object> notice() {
        List<String> installed=pool.list().stream().filter(item->"builtin".equals(item.get("source"))).map(item->String.valueOf(item.get("name"))).toList();
        List<String> available=names().stream().filter(name->!installed.contains(name)).toList();
        return Map.of("available",available,"installed",installed,"has_updates",!available.isEmpty());
    }
    private List<String> names(){try{String index=new String(new ClassPathResource("builtin-skills/index.txt").getInputStream().readAllBytes(),StandardCharsets.UTF_8);return index.lines().filter(line->!line.isBlank()).toList();}catch(IOException e){throw new IllegalStateException("Could not read packaged skill index",e);}}
    private Bundle read(String name)throws IOException {
        String content=new String(new ClassPathResource("builtin-skills/"+name+"/SKILL.md").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        Map<String,Object> references=new java.util.LinkedHashMap<>(),scripts=new java.util.LinkedHashMap<>(),extra=new java.util.LinkedHashMap<>();
        String marker="builtin-skills/"+name+"/";
        for(Resource resource:new PathMatchingResourcePatternResolver().getResources("classpath*:builtin-skills/"+name+"/**")){
            if(!resource.isReadable())continue;String url=resource.getURL().toString();int at=url.indexOf(marker);if(at<0)continue;String path=url.substring(at+marker.length());if(path.isBlank()||path.equals("SKILL.md"))continue;
            Object value=new String(resource.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if(path.startsWith("references/"))insert(references,path.substring(11),value);else if(path.startsWith("scripts/"))insert(scripts,path.substring(8),value);else insert(extra,path,value);
        }
        return new Bundle(content,references,scripts,extra);
    }
    @SuppressWarnings("unchecked") private static void insert(Map<String,Object> root,String path,Object value){Map<String,Object> current=root;String[] parts=path.split("/");for(int i=0;i<parts.length;i++){String part=parts[i];if(part.isBlank()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Unsafe built-in skill path");if(i==parts.length-1)current.put(part,value);else current=(Map<String,Object>)current.computeIfAbsent(part,ignored->new java.util.LinkedHashMap<String,Object>());}}
    private record Bundle(String content,Map<String,Object> references,Map<String,Object> scripts,Map<String,Object> extra) {}
}
