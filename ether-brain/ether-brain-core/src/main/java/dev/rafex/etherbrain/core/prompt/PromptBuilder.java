package dev.rafex.etherbrain.core.prompt;

import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ToolDescriptor;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import java.util.List;

public final class PromptBuilder {

    public ModelRequest build(ExecutionContext context, ToolRegistry toolRegistry) {
        List<ToolDescriptor> tools = toolRegistry.all().stream()
                .filter(t -> context.agentConfig().enabledTools().contains(t.name()))
                .map(t -> new ToolDescriptor(t.name(), t.description(), t.inputSchema()))
                .toList();

        return new ModelRequest(
                context.agentConfig().systemPrompt(),
                context.conversationState().messages(),
                tools
        );
    }
}
