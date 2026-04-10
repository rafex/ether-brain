package dev.rafex.etherbrain.ports.tools;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;

public interface ToolExecutor {

    ToolResult execute(ToolCall toolCall, ExecutionContext context);
}
