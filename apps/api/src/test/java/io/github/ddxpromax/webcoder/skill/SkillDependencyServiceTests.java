package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillDependencyServiceTests {
    @Test void reportsDeclaredMissingEnvironmentDependencies() {
        String content = """
            ---
            name: dependency-check
            description: Validate runtime dependencies
            requires:
              env: [WEBCODER_TEST_MISSING_ENV]
            ---
            Test.
            """;
        var result = new SkillDependencyService(null).check(Path.of("."), "dependency-check", content);
        assertThat(result).containsEntry("dependencies_met", false);
        assertThat(result.get("missing_dependencies")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).contains("Environment variable not set: WEBCODER_TEST_MISSING_ENV");
    }
}
