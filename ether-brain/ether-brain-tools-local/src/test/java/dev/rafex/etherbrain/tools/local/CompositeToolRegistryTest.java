package dev.rafex.etherbrain.tools.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeToolRegistryTest {

    @Test
    void resolvesToolFromFirstMatchingRegistry() {
        Tool firstEcho = new TestTool("echo");
        Tool secondEcho = new TestTool("echo");
        CompositeToolRegistry registry = new CompositeToolRegistry(List.of(
                registryWith(firstEcho),
                registryWith(secondEcho)
        ));

        Tool resolved = registry.find("echo").orElseThrow();

        assertSame(firstEcho, resolved);
    }

    @Test
    void mergesToolsWithoutDuplicatingNames() {
        CompositeToolRegistry registry = new CompositeToolRegistry(List.of(
                registryWith(new TestTool("echo"), new TestTool("current_time")),
                registryWith(new TestTool("echo"), new TestTool("calculator"))
        ));

        List<String> toolNames = registry.all().stream()
                .map(Tool::name)
                .toList();

        assertEquals(List.of("echo", "current_time", "calculator"), toolNames);
    }

    @Test
    void returnsEmptyWhenToolDoesNotExist() {
        CompositeToolRegistry registry = new CompositeToolRegistry(List.of(
                registryWith(new TestTool("echo"))
        ));

        assertTrue(registry.find("missing").isEmpty());
    }

    @Test
    void handlesEmptyRegistryList() {
        CompositeToolRegistry registry = new CompositeToolRegistry(List.of());

        assertTrue(registry.find("missing").isEmpty());
        assertEquals(List.of(), registry.all().stream().map(Tool::name).toList());
    }

    private static ToolRegistry registryWith(Tool... tools) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private record TestTool(String name) implements Tool {

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public ToolResult execute(String arguments, ExecutionContext context) {
            return new ToolResult(name, true, arguments);
        }
    }
}
