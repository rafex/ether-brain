package dev.rafex.etherbrain.core.tools;

import dev.rafex.etherbrain.common.ToolExecutionException;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolCall;
import dev.rafex.etherbrain.ports.tools.ToolExecutor;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;

public final class DefaultToolExecutor implements ToolExecutor {

    private final ToolRegistry toolRegistry;

    public DefaultToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ExecutionContext context) {
        if (!context.agentConfig().enabledTools().contains(toolCall.toolName())) {
            throw new ToolExecutionException("Tool not enabled: " + toolCall.toolName());
        }

        Tool tool = toolRegistry.find(toolCall.toolName())
                .orElseThrow(() -> new ToolExecutionException("Tool not found: " + toolCall.toolName()));

        try {
            return tool.execute(toolCall.arguments(), context);
        } catch (Exception exception) {
            throw new ToolExecutionException("Tool execution failed: " + toolCall.toolName(), exception);
        }
    }
}
