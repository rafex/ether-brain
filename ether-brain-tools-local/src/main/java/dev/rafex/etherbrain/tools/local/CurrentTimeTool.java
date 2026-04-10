package dev.rafex.etherbrain.tools.local;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.time.Instant;

public final class CurrentTimeTool implements Tool {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "Returns the current UTC time.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """;
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) {
        return new ToolResult(name(), true, Instant.now().toString());
    }
}
