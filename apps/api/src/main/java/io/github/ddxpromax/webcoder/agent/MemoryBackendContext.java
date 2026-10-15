package io.github.ddxpromax.webcoder.agent;

import java.nio.file.Path;
import java.util.Map;

public record MemoryBackendContext(
    String agentId,
    Path workspace,
    Map<String, Object> backendConfig,
    String language
) {}
