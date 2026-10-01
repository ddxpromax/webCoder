package io.github.ddxpromax.webcoder.explanation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record ExplainRequest(
    @NotBlank
    @Size(max = 20_000)
    String code,

    @NotBlank 
    @Size(max = 40)
    String language
) {
}
