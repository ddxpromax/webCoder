package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillHubSearchServiceTests {
    @Test void normalizesClawHubSearchPayloadShapes() {
        var results = SkillHubSearchService.normalize(Map.of("items", List.of(Map.of("slug", "review", "name", "Review", "description", "Review code", "version", "1.0"))));
        assertThat(results).containsExactly(Map.of("slug", "review", "name", "Review", "description", "Review code", "version", "1.0", "source_url", "https://clawhub.ai/skills/review"));
    }
}
