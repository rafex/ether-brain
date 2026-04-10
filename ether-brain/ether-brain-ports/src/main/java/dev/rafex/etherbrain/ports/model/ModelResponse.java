package dev.rafex.etherbrain.ports.model;

public sealed interface ModelResponse permits FinalAnswer, ToolRequest {
}
