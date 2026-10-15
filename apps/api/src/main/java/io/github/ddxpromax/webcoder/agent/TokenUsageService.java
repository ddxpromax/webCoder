package io.github.ddxpromax.webcoder.agent;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Majo-compatible daily and per-agent token accounting. */
@Service
public class TokenUsageService {
    private final TokenUsageRepository repository;

    public TokenUsageService(TokenUsageRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(
        String agentId,
        String chatId,
        String providerId,
        String model,
        int inputTokens,
        int outputTokens,
        long durationMs
    ) {
        repository.insert(
            agentId == null || agentId.isBlank() ? "default" : agentId,
            chatId,
            providerId == null ? "" : providerId,
            model == null ? "" : model,
            inputTokens,
            outputTokens,
            durationMs
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> details(
        String start,
        String end,
        String model,
        String provider
    ) {
        return repository.findByDateRange(start, end, model, provider)
            .stream().map(row -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("date", row.date());
                result.put("provider_id", row.providerId());
                result.put("model", row.model());
                result.put("prompt_tokens", row.promptTokens());
                result.put("completion_tokens", row.completionTokens());
                result.put("call_count", row.callCount());
                return result;
            }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(
        String start,
        String end,
        String model,
        String provider
    ) {
        var rows = repository.findByDateRange(
            start, end, model, provider
        );
        long prompt = 0;
        long completion = 0;
        long calls = 0;
        Map<String, MutableStats> byModel = new LinkedHashMap<>();
        Map<String, MutableStats> byDate = new LinkedHashMap<>();
        for (var row : rows) {
            prompt += row.promptTokens();
            completion += row.completionTokens();
            calls += row.callCount();
            String modelKey = row.providerId().isBlank()
                ? row.model() : row.providerId() + ":" + row.model();
            byModel.computeIfAbsent(modelKey, key ->
                new MutableStats(row.providerId(), row.model())
            ).add(row);
            byDate.computeIfAbsent(row.date(), key ->
                new MutableStats(null, null)
            ).add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_prompt_tokens", prompt);
        result.put("total_completion_tokens", completion);
        result.put("total_calls", calls);
        result.put("by_model", byModel);
        result.put("by_date", byDate);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> agentStatsSince(String start) {
        LocalDate date = start == null || start.isBlank()
            ? LocalDate.now().minusDays(30)
            : LocalDate.parse(start);
        return repository.agentStatsSince(date.atStartOfDay());
    }

    public static final class MutableStats {
        private final String providerId;
        private final String model;
        private long promptTokens;
        private long completionTokens;
        private long callCount;

        private MutableStats(String providerId, String model) {
            this.providerId = providerId;
            this.model = model;
        }

        private void add(TokenUsageRepository.DailyRecord row) {
            promptTokens += row.promptTokens();
            completionTokens += row.completionTokens();
            callCount += row.callCount();
        }

        @com.fasterxml.jackson.annotation.JsonProperty("provider_id")
        public String providerId() { return providerId; }

        @com.fasterxml.jackson.annotation.JsonProperty("model")
        public String model() { return model; }

        @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens")
        public long promptTokens() { return promptTokens; }

        @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens")
        public long completionTokens() { return completionTokens; }

        @com.fasterxml.jackson.annotation.JsonProperty("call_count")
        public long callCount() { return callCount; }
    }
}
