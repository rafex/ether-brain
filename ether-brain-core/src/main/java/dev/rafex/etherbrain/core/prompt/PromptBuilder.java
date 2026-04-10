package dev.rafex.etherbrain.core.prompt;

import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class PromptBuilder {

    public ModelRequest build(ExecutionContext context, ToolRegistry toolRegistry) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Message.Role.SYSTEM, systemInstructions(toolRegistry)));
        messages.addAll(context.conversationState().messages());
        return new ModelRequest(messages);
    }

    private String systemInstructions(ToolRegistry toolRegistry) {
        String toolsBlock = toolRegistry.all().stream()
                .map(this::renderTool)
                .collect(Collectors.joining("\n"));

        return """
                You are EtherBrain, a deterministic agent runtime assistant.
                You may answer directly or request exactly one tool.

                Available tools:
                %s

                When you need a tool, respond exactly with:
                TOOL:<tool_name>
                ARGS:<arguments>

                When you are ready to answer the user, respond exactly with:
                FINAL:<content>
                """.formatted(toolsBlock);
    }

    private String renderTool(Tool tool) {
        return "- %s: %s | schema: %s".formatted(
                tool.name(),
                tool.description(),
                tool.inputSchema().replace('\n', ' ').trim()
        );
    }
}
