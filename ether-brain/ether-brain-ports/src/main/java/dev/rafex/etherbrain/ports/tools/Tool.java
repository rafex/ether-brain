package dev.rafex.etherbrain.ports.tools;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;

public interface Tool {

    String name();

    String description();

    String inputSchema();

    ToolResult execute(String arguments, ExecutionContext context) throws Exception;
}
