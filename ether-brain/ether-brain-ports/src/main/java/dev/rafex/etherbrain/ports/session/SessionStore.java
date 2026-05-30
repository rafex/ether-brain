package dev.rafex.etherbrain.ports.session;

/**
 * Port of exit: loads and persists {@link ConversationState} for a given session.
 *
 * <p>Implementations may store sessions in memory ({@code InMemorySessionStore}),
 * on disk ({@code FileSessionStore}), or in a database.
 *
 * <p>The session lifecycle:
 * <ol>
 *   <li>{@link #load} — retrieve existing state or create a fresh one</li>
 *   <li>Messages are added during the agent run</li>
 *   <li>{@link #save} — persist the updated state after the run</li>
 * </ol>
 */
public interface SessionStore {

    /**
     * Loads the conversation state for the given session.
     * If no state exists, returns a new empty {@link ConversationState}.
     *
     * @param sessionId unique session identifier
     * @return the conversation state, never {@code null}
     */
    ConversationState load(String sessionId);

    /**
     * Persists the conversation state for the given session.
     *
     * @param sessionId unique session identifier
     * @param state     the state to persist
     */
    void save(String sessionId, ConversationState state);
}
