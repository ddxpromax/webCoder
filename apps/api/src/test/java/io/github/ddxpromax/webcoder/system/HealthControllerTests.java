package io.github.ddxpromax.webcoder.system;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HealthControllerTests {
    @Test void reportsMajoHealthStatus() { assertThat(new HealthController().health()).containsEntry("status", "ok"); }
}
