package dev.rafex.etherbrain.infra.model.demo;

import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;

/**
 * Demo model client that simulates a deterministic LLM.
 * <p>
 * Does not connect to any external service. Uses simple rules
 * to respond with tool calls or final answers based on input text.
 * Useful for development, testing, and demos.
 */
public final class DemoModelClient implements ModelClient {

    @Override
    public ModelResponse generate(ModelRequest request) {
        Message latest = request.messages().getLast();

        if (latest.role() == Message.Role.TOOL) {
            return new FinalAnswer("Resultado de tool: " + latest.content());
        }

        String content = latest.content().toLowerCase();
        if (content.contains("time") || content.contains("hora")) {
            return new ToolRequest("current_time", "{}");
        }

        return new ToolRequest("echo", latest.content());
    }
}
