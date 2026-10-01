package io.github.ddxpromax.webcoder.explanation;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;


@Service
public class ExplanationService {

    private final RestClient aiClient;

    public ExplanationService(
        RestClient.Builder builder,
        @Value("${webcoder.ai.base-url}") String baseUrl) {
            this.aiClient = builder.baseUrl(baseUrl).build();
    }

    public ExplainResponse explain(ExplainRequest request) {
        ExplainResponse response;

        try {
            response = aiClient.post()
                .uri("/internal/explanations")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ExplainResponse.class);
        } catch (RestClientException exception) {
            if (isTimeout(exception)) {
                throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "AI service timed out.",
                    exception);
            }

            if (exception instanceof ResourceAccessException) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI service is unavailable.",
                    exception);
            }

            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI service returned an invalid response.",
                exception);
        }

        if (response == null
            || !"mock".equals(response.mode())
            || response.explanation() == null
            || response.explanation().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI service returned an invalid response."
            );
        }

        return response;
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception;
            cause != null;
            cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException
                || cause instanceof SocketTimeoutException) {
                return true;
            }
        }

        return false;
    }
}
