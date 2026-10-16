package io.github.ddxpromax.webcoder.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majo's keyless live OpenRouter catalog and client-side compatible filters. */
@RestController @RequestMapping("/api/models/openrouter") @CrossOrigin(origins="*")
public class OpenRouterController {
    private final ObjectMapper mapper; private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public OpenRouterController(ObjectMapper mapper){this.mapper=mapper;}
    @GetMapping("/series") public Map<String,Object> series(){return Map.of("series",providers(models()));}
    @PostMapping("/discover-extended") public Map<String,Object> discover(@RequestBody(required=false) Map<String,Object> ignored){List<Map<String,Object>> models=models();Map<String,Object> result=new LinkedHashMap<>();result.put("success",!models.isEmpty());result.put("models",models);result.put("providers",providers(models));result.put("total_count",models.size());return result;}
    @PostMapping("/models/filter") public Map<String,Object> filter(@RequestBody Map<String,Object> body){List<String> providers=strings(body.get("providers")),inputs=strings(body.get("input_modalities"));Double max=body.get("max_input_price") instanceof Number number?number.doubleValue():null;List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> model:models()){if(!providers.isEmpty()&&!providers.contains(String.valueOf(model.get("provider"))))continue;List<String> available=strings(model.get("input_modalities"));if(!inputs.isEmpty()&&inputs.stream().noneMatch(available::contains))continue;if(max!=null){try{if(Double.parseDouble(map(model.get("pricing")).getOrDefault("prompt","0"))>max)continue;}catch(NumberFormatException ignored){}}result.add(model);}return Map.of("success",true,"models",result,"total_count",result.size());}
    private List<Map<String,Object>> models(){try{HttpRequest request=HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/models")).timeout(Duration.ofSeconds(30)).header("User-Agent","Majo/0.1").GET().build();HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)return List.of();List<Map<String,Object>> result=new ArrayList<>();for(JsonNode node:mapper.readTree(response.body()).path("data")){String id=node.path("id").asText();if(id.isBlank())continue;JsonNode architecture=node.path("architecture"),input=architecture.path("input_modalities");Map<String,Object> value=new LinkedHashMap<>();value.put("id",id);value.put("name",node.path("name").asText(id));value.put("supports_multimodal",contains(input,"image"));value.put("supports_image",contains(input,"image"));value.put("supports_video",contains(input,"video"));value.put("probe_source","openrouter");value.put("is_free",free(node.path("pricing")));value.put("provider",id.contains("/")?id.substring(0,id.indexOf('/')):"");value.put("input_modalities",nodes(input));value.put("output_modalities",nodes(architecture.path("output_modalities")));value.put("pricing",pricing(node.path("pricing")));result.add(value);}return result;}catch(Exception ignored){return List.of();}}
    private List<String> providers(List<Map<String,Object>> models){List<String> result=new ArrayList<>();for(Map<String,Object> model:models){String provider=String.valueOf(model.get("provider"));if(!provider.isBlank()&&!result.contains(provider))result.add(provider);}return result;}
    private boolean contains(JsonNode nodes,String value){for(JsonNode node:nodes)if(value.equals(node.asText()))return true;return false;}
    private List<String> nodes(JsonNode nodes){List<String> result=new ArrayList<>();for(JsonNode node:nodes)result.add(node.asText());return result;}
    private boolean free(JsonNode pricing){boolean any=false;for(JsonNode value:pricing)try{if(Double.parseDouble(value.asText())!=0)return false;any=true;}catch(NumberFormatException ignored){}return any;}
    private Map<String,String> pricing(JsonNode pricing){Map<String,String> result=new LinkedHashMap<>();pricing.fields().forEachRemaining(entry->result.put(entry.getKey(),entry.getValue().asText()));return result;}
    @SuppressWarnings("unchecked") private Map<String,String> map(Object value){return value instanceof Map<?,?> map?(Map<String,String>)(Map<?,?>)map:Map.of();}
    private List<String> strings(Object value){return value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();}
}
