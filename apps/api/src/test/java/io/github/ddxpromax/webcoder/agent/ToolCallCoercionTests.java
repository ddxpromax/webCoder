package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallCoercionTests {
    @Test void repairsOnlySchemaDeclaredStringFields() {
        var result = ToolCallCoercion.coerceStringFields(Map.of("id", 42, "limit", 12), Map.of("properties", Map.of("id", Map.of("type", "string"), "limit", Map.of("type", "integer"))));
        assertThat(result.input()).containsEntry("id", "42").containsEntry("limit", 12);
        assertThat(result.changed()).isTrue();
    }
}
