package io.github.ddxpromax.webcoder.conversation;

public enum AssistantMessageStatus {
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String databaseValue;

    AssistantMessageStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
