package io.github.ddxpromax.webcoder.mcp;

public class McpNotFoundException extends RuntimeException {
    public McpNotFoundException(String key) {
        super("MCP client '" + key + "' not found");
    }
}
