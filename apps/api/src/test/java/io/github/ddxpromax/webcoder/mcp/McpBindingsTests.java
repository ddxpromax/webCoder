package io.github.ddxpromax.webcoder.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class McpBindingsTests {
    @Test
    void resolvesCredentialFormatsForOauthBearerBindings() {
        assertThat(McpBindings.resolve(Map.of("Authorization", Map.of(
            "source", "credential", "credential", "oauth",
            "field", "access_token", "format", "Bearer {value}"
        )), Map.of("access_token", "secret-token")))
            .containsEntry("Authorization", "Bearer secret-token");
    }
}
