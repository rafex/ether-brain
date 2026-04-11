package dev.rafex.etherbrain.tools.local;

import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CompositeToolRegistry implements ToolRegistry {

    private final List<ToolRegistry> registries;

    public CompositeToolRegistry(List<ToolRegistry> registries) {
        this.registries = List.copyOf(registries);
    }

    @Override
    public Optional<Tool> find(String name) {
        return registries.stream()
                .map(registry -> registry.find(name))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public Collection<Tool> all() {
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (ToolRegistry registry : registries) {
            for (Tool tool : registry.all()) {
                tools.putIfAbsent(tool.name(), tool);
            }
        }
        return List.copyOf(tools.values());
    }
}
