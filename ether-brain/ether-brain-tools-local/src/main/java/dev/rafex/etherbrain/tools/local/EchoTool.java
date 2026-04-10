package dev.rafex.etherbrain.tools.local;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;

public final class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Returns the provided argument unchanged.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"string\"}";
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) {
        return new ToolResult(name(), true, arguments);
    }
}
