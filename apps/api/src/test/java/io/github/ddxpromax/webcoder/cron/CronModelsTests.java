package io.github.ddxpromax.webcoder.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CronModelsTests {
    @Test
    void normalizesNumericDayOfWeekAndAppliesMajoDefaults() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "Daily review");
        spec.put("task_type", "text"); spec.put("text", "Review pull requests");
        spec.put("schedule", new LinkedHashMap<>(Map.of("type", "cron", "cron", "30 8 * * 1-5")));
        CronModels.validate(spec);
        assertThat(CronModels.schedule(spec).get("cron")).isEqualTo("30 8 * * MON-FRI");
        assertThat(CronModels.dispatch(spec).get("channel")).isEqualTo("console");
        assertThat(spec.get("save_result_to_inbox")).isEqualTo(false);
    }

    @Test
    void rejectsSilentTextAndIncompleteAgentRequests() {
        Map<String, Object> silentText = new LinkedHashMap<>(Map.of("name", "Notice", "task_type", "text", "text", "hi", "schedule", Map.of("type", "cron", "cron", "0 9 * * *"), "dispatch", Map.of("silent", true)));
        assertThatThrownBy(() -> CronModels.validate(silentText)).hasMessageContaining("silent delivery");
        Map<String, Object> agent = new LinkedHashMap<>(Map.of("name", "Review", "task_type", "agent", "schedule", Map.of("type", "cron", "cron", "0 9 * * *")));
        assertThatThrownBy(() -> CronModels.validate(agent)).hasMessageContaining("request is missing");
    }
}
