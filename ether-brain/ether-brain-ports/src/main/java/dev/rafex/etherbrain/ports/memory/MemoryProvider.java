package dev.rafex.etherbrain.ports.memory;

/**
 * Port for semantic memory — stores and retrieves context across conversations.
 *
 * <p>This is separate from {@link dev.rafex.etherbrain.ports.session.SessionStore}:
 * <ul>
 *   <li>{@code SessionStore} keeps the exact ordered message history for one session.</li>
 *   <li>{@code MemoryProvider} keeps a semantic index — you query it by meaning,
 *       not by position — and can span multiple sessions.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe. All methods may throw {@link Exception}
 * but callers should treat failures as non-fatal and degrade gracefully.
 */
public interface MemoryProvider {

    /**
     * Recalls relevant context for the current user query.
     *
     * <p>The implementation may search a short-term scratchpad, a long-term
     * knowledge base, or both. Returns {@code null} or blank if nothing relevant
     * is found.
     *
     * @param sessionId  the current EtherBrain session identifier
     * @param query      the user's current message (used as search anchor)
     * @return formatted context string to inject into the prompt, or {@code null}
     */
    String recall(String sessionId, String query) throws Exception;

    /**
     * Stores a completed turn (user + agent) in the session's working memory.
     *
     * <p>Called automatically after each agent response. Implementations should
     * not block the calling thread — use virtual threads or fire-and-forget.
     *
     * @param sessionId    the current EtherBrain session identifier
     * @param userMessage  the user's message
     * @param agentAnswer  the agent's final answer
     */
    void remember(String sessionId, String userMessage, String agentAnswer) throws Exception;
}
