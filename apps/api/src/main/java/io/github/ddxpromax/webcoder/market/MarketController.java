package io.github.ddxpromax.webcoder.market;

import io.github.ddxpromax.webcoder.agent.AgentStore;
import io.github.ddxpromax.webcoder.skill.SkillWorkspaceService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Majo's local skill-market discovery API, backed by installed workspace skills. */
@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "*")
public class MarketController {
    private final AgentStore agents;
    private final SkillWorkspaceService skills;

    public MarketController(AgentStore agents, SkillWorkspaceService skills) {
        this.agents = agents;
        this.skills = skills;
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("key", "majo-pool");
        provider.put("label", "Majo Skill Pool");
        provider.put("available", true);
        provider.put("reason", null);
        provider.put("supports_browse", true);
        return List.of(provider);
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestParam(defaultValue = "en") String language) {
        return List.of(category("general", "General"), category("coding", "Coding"),
            category("browser", "Browser"), category("communication", "Communication"),
            category("productivity", "Productivity"));
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String query = text(request.get("query")).toLowerCase(Locale.ROOT);
        int limit = positiveLimit(request.get("limit"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> profile : agents.listProfiles()) {
            String agentId = text(profile.get("id"));
            Path workspace = agents.workspaceDirForAgent(agentId);
            for (Map<String, Object> skill : skills.list(workspace)) {
                String name = text(skill.get("name"));
                String description = text(skill.get("description"));
                if (!query.isBlank() && !(name + " " + description).toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", "majo-pool");
                result.put("slug", name);
                result.put("name", name);
                result.put("description", description.isBlank() ? null : description);
                result.put("source_url", "");
                result.put("version", skill.get("version_text"));
                result.put("author", null);
                result.put("icon_url", null);
                result.put("stats", null);
                result.put("agent_id", agentId);
                results.add(result);
                if (results.size() >= limit) return response(results);
            }
        }
        return response(results);
    }

    private Map<String, Object> response(List<Map<String, Object>> results) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("has_more", false);
        page.put("total", results.size());
        return Map.of("results", results, "errors", List.of(), "by_provider", Map.of("majo-pool", page));
    }

    private Map<String, Object> category(String id, String label) { return Map.of("id", id, "label", label); }
    private int positiveLimit(Object value) { try { return Math.max(1, Math.min(value == null ? 10 : Integer.parseInt(String.valueOf(value)), 100)); } catch (NumberFormatException exception) { return 10; } }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
