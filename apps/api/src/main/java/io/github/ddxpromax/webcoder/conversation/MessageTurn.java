package io.github.ddxpromax.webcoder.conversation;

public record MessageTurn(
    MessageResponse userMessage,
    MessageResponse assistantMessage) {
}
