package dev.rafex.etherbrain.ports.model;

public record ToolRequest(String toolName, String arguments) implements ModelResponse {
}
