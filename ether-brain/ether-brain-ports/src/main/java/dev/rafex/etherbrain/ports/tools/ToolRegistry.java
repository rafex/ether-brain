package dev.rafex.etherbrain.ports.tools;

import java.util.Collection;
import java.util.Optional;

/**
 * Port of query: discovers and retrieves {@link Tool}s available to the agent.
 *
 * <p>The registry is the single source of truth for which tools exist.
 * {@code PromptBuilder} reads {@link #all()} to build the tool list for the model;
 * {@code DefaultToolExecutor} uses {@link #find(String)} to locate the tool
 * the model requested.
 *
 * <p>Use {@link dev.rafex.etherbrain.ports.runtime.AgentConfig#enabledTools()}
 * to control which tools from this registry are exposed to the model.
 *
 * @see Tool
 */
public interface ToolRegistry {

    /**
     * Finds a tool by its exact name.
     *
     * @param name the tool identifier
     * @return the tool wrapped in {@link Optional}, or empty if not registered
     */
    Optional<Tool> find(String name);

    /**
     * Returns all tools registered in this registry.
     *
     * @return unmodifiable view of all tools
     */
    Collection<Tool> all();
}
