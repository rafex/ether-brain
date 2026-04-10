package dev.rafex.etherbrain.tools.local;

import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public InMemoryToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<Tool> all() {
        return tools.values();
    }
}
