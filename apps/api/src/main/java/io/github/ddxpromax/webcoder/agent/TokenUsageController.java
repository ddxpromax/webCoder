package io.github.ddxpromax.webcoder.agent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/token-usage")
public class TokenUsageController {
    private final TokenUsageService usage;

    public TokenUsageController(TokenUsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    public Map<String, Object> summary(
        @RequestParam(required = false) String start_date,
        @RequestParam(required = false) String end_date,
        @RequestParam(required = false) String model,
        @RequestParam(required = false) String provider
    ) {
        return usage.summary(
            start(start_date), end(end_date), model, provider
        );
    }

    @GetMapping("/details")
    public List<Map<String, Object>> details(
        @RequestParam(required = false) String start_date,
        @RequestParam(required = false) String end_date,
        @RequestParam(required = false) String model,
        @RequestParam(required = false) String provider
    ) {
        return usage.details(
            start(start_date), end(end_date), model, provider
        );
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(
        @RequestParam(required = false) String start_date
    ) {
        return Map.of("agents", usage.agentStatsSince(start_date));
    }

    private static String start(String value) {
        return value == null || value.isBlank()
            ? "1970-01-01" : value;
    }

    private static String end(String value) {
        return value == null || value.isBlank()
            ? LocalDate.now().toString() : value;
    }
}
