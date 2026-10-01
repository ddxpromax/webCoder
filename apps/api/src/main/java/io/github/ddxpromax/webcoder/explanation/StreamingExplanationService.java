package io.github.ddxpromax.webcoder.explanation;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;


@Service
public class StreamingExplanationService {

    private static final ParameterizedTypeReference<
        ServerSentEvent<String>
    > SERVER_SENT_EVENT_TYPE = new ParameterizedTypeReference<>() {};

    private final WebClient aiClient;

    public StreamingExplanationService(WebClient aiStreamingClient) {
        this.aiClient = aiStreamingClient;
    }

    public Flux<ServerSentEvent<String>> stream(ExplainRequest request) {
        return aiClient.post()
            .uri("/internal/explanations/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(SERVER_SENT_EVENT_TYPE)
            .onErrorResume(
                WebClientRequestException.class,
                exception -> Flux.just(errorEvent(
                    "The AI service is temporarily unavailable."
                ))
            )
            .onErrorResume(
                WebClientResponseException.class,
                exception -> Flux.just(errorEvent(
                    "The AI service could not process the request."
                ))
            );
    }

    private static ServerSentEvent<String> errorEvent(String message) {
        return ServerSentEvent.<String>builder(message)
            .event("error")
            .build();
    }
}
