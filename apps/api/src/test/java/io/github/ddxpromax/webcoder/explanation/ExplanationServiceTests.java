package io.github.ddxpromax.webcoder.explanation;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;


public class ExplanationServiceTests {
    
    private MockRestServiceServer server;
    private ExplanationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer.bindTo(builder).build();
        service = new ExplanationService(
            builder,
            "http://ai-service.test");
    }

    @Test
    void connectionFailureBecomes503() {
        server.expect(requestTo("http://ai-service.test/internal/explanations"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withException(
                new ConnectException("Connection refused")));
        
        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.explain(
                new ExplainRequest("print(1)", "python")));
        
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode());

        server.verify();
    }

    @Test 
    void timeoutBecomes504() {
        server.expect(requestTo("http://ai-service.test/internal/explanations"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withException(
                new HttpTimeoutException("Request timed out")));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.explain(
                new ExplainRequest("print(1)", "python")));

        assertEquals(
            HttpStatus.GATEWAY_TIMEOUT,
            exception.getStatusCode());
        
        server.verify();
    }
}
