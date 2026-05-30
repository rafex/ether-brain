package dev.rafex.etherbrain.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.common.ToolExecutionException;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolCall;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultToolExecutorTest {

    @Test
    void executesEnabledTool() {
        ToolRegistry registry = registryWith(echoTool());
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ExecutionContext ctx = context(Set.of("echo"));

        ToolResult result = executor.execute(new ToolCall("echo", "hello"), ctx);

        assertTrue(result.success());
        assertEquals("hello", result.content());
    }

    @Test
    void throwsWhenToolNotEnabled() {
        ToolRegistry registry = registryWith(echoTool());
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ExecutionContext ctx = context(Set.of());   // echo not enabled

        assertThrows(ToolExecutionException.class,
                () -> executor.execute(new ToolCall("echo", "hello"), ctx));
    }

    @Test
    void throwsWhenToolNotFound() {
        ToolRegistry registry = registryWith();   // empty registry
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ExecutionContext ctx = context(Set.of("ghost"));

        assertThrows(ToolExecutionException.class,
                () -> executor.execute(new ToolCall("ghost", "{}"), ctx));
    }

    @Test
    void wrapsExceptionFromTool() {
        Tool explosive = new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "explodes"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolResult execute(String arguments, ExecutionContext context)
                    throws Exception {
                throw new RuntimeException("ka-boom");
            }
        };
        ToolRegistry registry = registryWith(explosive);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ExecutionContext ctx = context(Set.of("boom"));

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> executor.execute(new ToolCall("boom", "{}"), ctx));
        assertTrue(ex.getMessage().contains("boom"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ExecutionContext context(Set<String> enabledTools) {
        return new ExecutionContext("s1", new ConversationState(),
                new AgentConfig(4, Duration.ofSeconds(5), enabledTools));
    }

    private ToolRegistry registryWith(Tool... tools) {
        return new ToolRegistry() {
            @Override public Optional<Tool> find(String name) {
                return List.of(tools).stream().filter(t -> t.name().equals(name)).findFirst();
            }
            @Override public Collection<Tool> all() { return List.of(tools); }
        };
    }

    private Tool echoTool() {
        return new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "echo"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolResult execute(String arguments, ExecutionContext context) {
                return new ToolResult(name(), true, arguments);
            }
        };
    }
}
