package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.ports.runtime.AgentRegistry;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-process agent registry.
 *
 * <p>Holds a map of {@code agentName → AgentRunner} so that one agent can
 * discover and delegate to another without going over HTTP.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LocalAgentRegistry registry = new LocalAgentRegistry();
 *
 * AgentRuntime researcher = buildResearcherAgent();
 * AgentRuntime writer     = buildWriterAgent();
 *
 * registry.register(researcher);  // researcher.agentName() → "researcher"
 * registry.register(writer);      // writer.agentName()     → "writer"
 *
 * // Wrap each agent as a tool for the orchestrator:
 * toolRegistry.register(new AgentTool(researcher, "Specialised research agent"));
 * toolRegistry.register(new AgentTool(writer,     "Specialised writing agent"));
 * }</pre>
 */
public final class LocalAgentRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentRunner> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentRunner runner) {
        agents.put(runner.agentName(), runner);
        System.out.println("[AgentRegistry] registered agent: " + runner.agentName());
    }

    @Override
    public Optional<AgentRunner> get(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    @Override
    public Collection<AgentRunner> all() {
        return Collections.unmodifiableCollection(agents.values());
    }
}
