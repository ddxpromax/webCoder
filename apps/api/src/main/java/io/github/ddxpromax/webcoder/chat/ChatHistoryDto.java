package io.github.ddxpromax.webcoder.chat;

import java.util.List;
import java.util.Map;

public record ChatHistoryDto(
    List<Map<String, Object>> messages,
    String status
) {}