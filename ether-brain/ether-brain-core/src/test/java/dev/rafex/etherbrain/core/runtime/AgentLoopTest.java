package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rafex.etherbrain.common.ToolExecutionException;
import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    @Test
    void executesToolThenReturnsFinalAnswer() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "What time is it?"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = new AgentLoop(
                new FakeModelClient(),
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        String answer = loop.run(new ExecutionContext(
                "session-1",
                state,
                new AgentConfig(4, Duration.ofSeconds(5), Set.of("echo"))
        ));

        assertEquals("Tool said: pong", answer);
    }

    @Test
    void failsWhenToolDoesNotExist() {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "Run missing"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of());
        AgentLoop loop = new AgentLoop(
                request -> new ToolRequest("missing", "{}"),
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        assertThrows(ToolExecutionException.class, () -> loop.run(new ExecutionContext(
                "session-2",
                state,
                new AgentConfig(2, Duration.ofSeconds(5), Set.of("missing"))
        )));
    }

    private static final class FakeModelClient implements ModelClient {

        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasToolMessage = request.messages().stream()
                    .anyMatch(message -> message.role() == Message.Role.TOOL);

            if (!hasToolMessage) {
                return new ToolRequest("echo", "pong");
            }

            return new FinalAnswer("Tool said: pong");
        }
    }

    private static final class EchoTool implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Returns the provided argument";
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

    private record TestToolRegistry(Collection<Tool> tools) implements ToolRegistry {

        @Override
        public Optional<Tool> find(String name) {
            return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
        }

        @Override
        public Collection<Tool> all() {
            return tools;
        }
    }
}
