package dev.rafex.etherbrain.ports.runtime;

/**
 * Port that represents a named, callable agent.
 *
 * <p>Any object that can accept a message and return an answer qualifies.
 * This interface is implemented by {@code AgentRuntime} and can be used
 * by {@code AgentTool} to embed one agent inside another without HTTP overhead.
 *
 * <h2>Multi-agent topology example</h2>
 * <pre>
 * AgentRunner researcher = ...;  // specialised research agent
 * AgentRunner writer     = ...;  // specialised writing agent
 *
 * // Register both as tools of the orchestrator:
 * registry.register(new AgentTool(researcher));
 * registry.register(new AgentTool(writer));
 * </pre>
 */
public interface AgentRunner {

    /** A short unique identifier for this agent. Used as tool name. */
    String agentName();

    /**
     * A human-readable description of what this agent does.
     * Shown to the orchestrating model so it knows when to delegate.
     */
    String agentDescription();

    /**
     * Run one turn of the agent.
     *
     * @param sessionId   conversation session identifier
     * @param userMessage the message / task to process
     * @return the agent's final answer
     * @throws Exception on unrecoverable failure
     */
    String run(String sessionId, String userMessage) throws Exception;

    /**
     * Run with cancellation support.
     * Default implementation ignores the token — override for full support.
     */
    default String run(String sessionId, String userMessage,
                       CancellationToken token) throws Exception {
        return run(sessionId, userMessage);
    }
}
