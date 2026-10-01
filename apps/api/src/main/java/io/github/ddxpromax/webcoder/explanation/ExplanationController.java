package io.github.ddxpromax.webcoder.explanation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController 
@RequestMapping("/api/explanations")
public class ExplanationController {
    
    private final ExplanationService explanationService;

    public ExplanationController(
        ExplanationService explanationService) {
            this.explanationService = explanationService;
    }

    @PostMapping
    public ExplainResponse explain(
        @Valid @RequestBody ExplainRequest request) {
            return explanationService.explain(request);
    }
}
