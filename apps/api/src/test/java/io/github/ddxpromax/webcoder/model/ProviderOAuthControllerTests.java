package io.github.ddxpromax.webcoder.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderOAuthControllerTests {
    @Test void startsAndPollsMajoOpenRouterBrowserAuthorization() {
        ProviderOAuthController controller = new ProviderOAuthController(mock(ProviderRepository.class), new ObjectMapper(), mock(HttpClient.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/providers/openrouter/oauth/start"); request.setScheme("https"); request.addHeader("Host", "majo.local");
        @SuppressWarnings("unchecked") var body = (java.util.Map<String, Object>) controller.start("openrouter", request).getBody();
        assertThat(body.get("authorize_url").toString()).contains("openrouter.ai/auth").contains("majo.local");
        assertThat(controller.status("openrouter", String.valueOf(body.get("state")))).containsEntry("status", "pending");
        assertThat(controller.start("other", request).getStatusCode().value()).isEqualTo(404);
    }
}
