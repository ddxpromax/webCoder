package io.github.ddxpromax.webcoder.agent;

import java.util.Map;

public record MemoryHit(
    String source,
    String snippet,
    double score,
    Map<String, Object> metadata
) {}
