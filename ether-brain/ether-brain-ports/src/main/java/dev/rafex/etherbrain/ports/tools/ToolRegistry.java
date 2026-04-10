package dev.rafex.etherbrain.ports.tools;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {

    Optional<Tool> find(String name);

    Collection<Tool> all();
}
