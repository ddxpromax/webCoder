package io.github.ddxpromax.webcoder.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Majo ClawHub search client. Network access occurs only for an explicit search request. */
@Service
public class SkillHubSearchService {
    private static final String HUB = "https://clawhub.ai/api/v1/search";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper;
    public SkillHubSearchService(ObjectMapper mapper) { this.mapper = mapper; }
    public List<Map<String, Object>> search(String query, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        try {
            URI uri = URI.create(HUB + "?q=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8) + "&limit=" + limit);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new IllegalStateException("ClawHub returned HTTP " + response.statusCode() + ".");
            return normalize(mapper.readValue(response.body(), new TypeReference<Object>() {}));
        } catch (Exception exception) { throw new IllegalStateException("Skill Hub search failed: " + exception.getMessage(), exception); }
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> normalize(Object body) {
        Object values = body instanceof Map<?, ?> map ? first(map, "items", "skills", "results", "data") : body;
        if (!(values instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) if (entry instanceof Map<?, ?> raw) {
            Map<String, Object> source = new LinkedHashMap<>(); raw.forEach((key, value) -> source.put(String.valueOf(key), value));
            String slug = text(source.get("slug")); if (slug.isBlank()) slug = text(source.get("name")); if (slug.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>(); item.put("slug", slug); item.put("name", nonBlank(source.get("name"), slug)); item.put("description", nonBlank(source.get("description"), text(source.get("summary")))); item.put("version", text(source.get("version"))); item.put("source_url", nonBlank(source.get("url"), "https://clawhub.ai/skills/" + slug)); result.add(item);
        }
        return result;
    }
    private static Object first(Map<?, ?> map, String... keys) { for (String key : keys) if (map.get(key) instanceof List<?>) return map.get(key); return List.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String nonBlank(Object value, String fallback) { String text = text(value); return text.isBlank() ? fallback : text; }
}
