package dev.rafex.etherbrain.ports.model;

public record ToolRequest(String toolCallId, String toolName, String arguments) implements ModelResponse {

    public ToolRequest(String toolName, String arguments) {
        this(null, toolName, arguments);
    }
}
