package dev.rafex.etherbrain.bootstrap;

import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;

/**
 * Deterministic stub {@link ModelClient} used when {@code LLM_URL} is not set.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If the last user message contains "time" or "hora" → calls {@code current_time}</li>
 *   <li>If there is a tool result in the conversation → returns it as a final answer</li>
 *   <li>Otherwise → echoes the last user message via the {@code echo} tool</li>
 * </ul>
 *
 * <p>Useful for integration tests, CI pipelines, and offline demos.
 * Not suitable for production use.
 */
final class StubModelClient implements ModelClient {

    @Override
    public ModelResponse generate(ModelRequest request) {
        boolean hasToolResult = request.messages().stream()
                .anyMatch(m -> m.role() == Message.Role.TOOL);

        if (hasToolResult) {
            String content = request.messages().stream()
                    .filter(m -> m.role() == Message.Role.TOOL)
                    .reduce((a, b) -> b)
                    .map(Message::content)
                    .orElse("(empty)");
            return new FinalAnswer("Resultado de tool: " + content);
        }

        Message latest  = request.messages().getLast();
        String  content = latest.content().toLowerCase();
        if (content.contains("time") || content.contains("hora")) {
            return new ToolRequest("current_time", "{}");
        }
        return new ToolRequest("echo", latest.content());
    }
}
