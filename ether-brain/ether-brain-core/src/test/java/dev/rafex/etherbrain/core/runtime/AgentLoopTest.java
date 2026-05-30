package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.common.AgentException;
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
    void continuesLoopWhenToolErrors() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "Run failing tool then answer"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));

        // First call requests a non-existent tool; second call (after error) returns final answer
        AgentLoop loop = new AgentLoop(
                new FakeModelClientWithRecovery(),
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        String answer = loop.run(new ExecutionContext(
                "session-recovery",
                state,
                new AgentConfig(4, Duration.ofSeconds(5), Set.of("echo"))
        ));

        assertEquals("Recovered after tool error", answer);
    }

    @Test
    void exhaustsMaxStepsWhenToolKeepsFailing() {
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

        // Error is caught, loop continues, eventually hits maxSteps
        AgentException ex = assertThrows(AgentException.class, () -> loop.run(new ExecutionContext(
                "session-missing",
                state,
                new AgentConfig(2, Duration.ofSeconds(5), Set.of("missing"))
        )));
        assertTrue(ex.getMessage().contains("Max steps exceeded"));
    }

    @Test
    void trimsMessageWindowDuringExecution() throws Exception {
        // Window of 6: USER + up to 2 full tool turns (ASSISTANT+TOOL) + final ASSISTANT
        ConversationState state = new ConversationState(6);
        state.add(new Message(Message.Role.USER, "trim test"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = new AgentLoop(
                new FakeModelClient(),
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        loop.run(new ExecutionContext(
                "session-trim",
                state,
                new AgentConfig(4, Duration.ofSeconds(5), Set.of("echo"))
        ));

        // State must stay within the configured window
        assertTrue(state.size() <= 6,
                "Expected <= 6 messages, got " + state.size());
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private static final class FakeModelClient implements ModelClient {
        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL);
            return hasToolResult
                    ? new FinalAnswer("Tool said: pong")
                    : new ToolRequest("echo", "pong");
        }
    }

    /** Returns a missing tool on step 1; returns FinalAnswer when it sees an error TOOL message. */
    private static final class FakeModelClientWithRecovery implements ModelClient {
        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasErrorToolResult = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL
                            && m.content().startsWith("Error:"));
            return hasErrorToolResult
                    ? new FinalAnswer("Recovered after tool error")
                    : new ToolRequest("missing_tool", "{}");
        }
    }

    private static final class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Returns the provided argument"; }
        @Override public String inputSchema() { return "{\"type\":\"string\"}"; }
        @Override
        public ToolResult execute(String arguments, ExecutionContext context) {
            return new ToolResult(name(), true, arguments);
        }
    }

    private record TestToolRegistry(Collection<Tool> tools) implements ToolRegistry {
        @Override
        public Optional<Tool> find(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
        @Override
        public Collection<Tool> all() { return tools; }
    }
}
