package dev.rafex.etherbrain.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelRequest;
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

class PromptBuilderTest {

    @Test
    void populatesSystemInstructions() {
        ModelRequest request = build(Set.of(), List.of());
        assertFalse(request.system().isBlank(), "System instructions must not be blank");
    }

    @Test
    void includesOnlyEnabledTools() {
        // Registry has echo + current_time; only echo is enabled
        ModelRequest request = build(
                Set.of("echo"),
                List.of(namedTool("echo"), namedTool("current_time")));

        assertEquals(1, request.tools().size());
        assertEquals("echo", request.tools().getFirst().name());
    }

    @Test
    void includesNoToolsWhenNoneEnabled() {
        ModelRequest request = build(
                Set.of(),
                List.of(namedTool("echo"), namedTool("current_time")));

        assertTrue(request.tools().isEmpty());
    }

    @Test
    void includesAllEnabledTools() {
        ModelRequest request = build(
                Set.of("echo", "current_time"),
                List.of(namedTool("echo"), namedTool("current_time")));

        assertEquals(2, request.tools().size());
    }

    @Test
    void includesConversationMessages() {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "Hello"));
        state.add(new Message(Message.Role.ASSISTANT, "Hi there"));

        ModelRequest request = new PromptBuilder().build(
                new ExecutionContext("s1", state,
                        new AgentConfig(4, Duration.ofSeconds(5), Set.of())),
                new EmptyRegistry());

        assertEquals(2, request.messages().size());
        assertEquals(Message.Role.USER, request.messages().getFirst().role());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ModelRequest build(Set<String> enabledTools, List<Tool> tools) {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "test"));
        ExecutionContext ctx = new ExecutionContext(
                "session-test", state,
                new AgentConfig(4, Duration.ofSeconds(5), enabledTools));
        return new PromptBuilder().build(ctx, new ListRegistry(tools));
    }

    private Tool namedTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc " + name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolResult execute(String arguments, ExecutionContext context) {
                return new ToolResult(name, true, arguments);
            }
        };
    }

    private record ListRegistry(List<Tool> tools) implements ToolRegistry {
        @Override public Optional<Tool> find(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
        @Override public Collection<Tool> all() { return tools; }
    }

    private static final class EmptyRegistry implements ToolRegistry {
        @Override public Optional<Tool> find(String name) { return Optional.empty(); }
        @Override public Collection<Tool> all() { return List.of(); }
    }
}
