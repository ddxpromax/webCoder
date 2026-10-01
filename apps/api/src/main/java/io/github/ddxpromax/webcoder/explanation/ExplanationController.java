package io.github.ddxpromax.webcoder.explanation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;

import reactor.core.publisher.Flux;


@RestController 
@RequestMapping("/api/explanations")
public class ExplanationController {
    
    private final ExplanationService explanationService;
    private final StreamingExplanationService streamingExplanationService;

    public ExplanationController(
        ExplanationService explanationService,
        StreamingExplanationService streamingExplanationService) {
            this.explanationService = explanationService;
            this.streamingExplanationService = streamingExplanationService;
    }

    @PostMapping
    public ExplainResponse explain(
        @Valid @RequestBody ExplainRequest request) {
            return explanationService.explain(request);
    }

    @PostMapping(
        path = "/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> explainStream(
        @Valid @RequestBody ExplainRequest request) {
            return streamingExplanationService.stream(request);
        }
}
