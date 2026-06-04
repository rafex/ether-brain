package dev.rafex.etherbrain.ports.runtime;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry that holds named {@link AgentRunner} instances and allows
 * agents to discover and delegate to one another without HTTP overhead.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Register agents at startup: {@code registry.register(runner)}</li>
 *   <li>Pass the registry to {@code AgentTool} or query it from tools at runtime.</li>
 *   <li>The orchestrator's {@code AgentTool} calls {@link #get(String)} to look
 *       up the sub-agent and invoke it.</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * AgentRegistry registry = new LocalAgentRegistry();
 * registry.register(researcherRuntime);
 * registry.register(writerRuntime);
 *
 * // In the orchestrator's tools:
 * registry.get("researcher").ifPresent(r -> r.run(sessionId, task));
 * }</pre>
 */
public interface AgentRegistry {

    /**
     * Register an agent. Replaces any existing registration with the same name.
     *
     * @param runner the agent to register
     */
    void register(AgentRunner runner);

    /**
     * Looks up an agent by its {@link AgentRunner#agentName() name}.
     *
     * @param name agent name
     * @return the registered runner, or empty if not found
     */
    Optional<AgentRunner> get(String name);

    /** Returns all registered agents (unordered, unmodifiable view). */
    Collection<AgentRunner> all();

    /** Returns the number of registered agents. */
    default int size() { return all().size(); }
}
