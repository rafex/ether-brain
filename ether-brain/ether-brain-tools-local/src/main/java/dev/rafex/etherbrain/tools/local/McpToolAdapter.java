package dev.rafex.etherbrain.tools.local;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;

/**
 * Adapta una tool de un servidor MCP al puerto {@link Tool} de EtherBrain.
 *
 * <p>Cada instancia representa una tool individual descubierta mediante
 * {@link McpClient#listTools()}. Todas las tools de un mismo servidor
 * comparten la misma instancia de {@link McpClient}.
 */
public final class McpToolAdapter implements Tool {

    private final McpClient client;
    private final String    toolName;
    private final String    description;
    private final String    inputSchema;

    public McpToolAdapter(McpClient client, McpClient.McpToolInfo info) {
        this.client      = client;
        this.toolName    = info.name();
        this.description = info.description();
        this.inputSchema = info.inputSchema();
    }

    @Override public String name()        { return toolName; }
    @Override public String description() { return description; }
    @Override public String inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        try {
            String result = client.callTool(toolName, arguments);
            return new ToolResult(toolName, true, result);
        } catch (Exception e) {
            return new ToolResult(toolName, false,
                    "MCP tool '" + toolName + "' failed: " + e.getMessage());
        }
    }
}
