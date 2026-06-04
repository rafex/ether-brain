package dev.rafex.etherbrain.ports.runtime;

import dev.rafex.etherbrain.ports.session.ConversationState;

/**
 * Runtime context passed to the agent loop on each step.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code memoryContext} — relevant past context retrieved from
 *       {@link dev.rafex.etherbrain.ports.memory.MemoryProvider}, injected into
 *       the prompt. Never persisted in the session store.</li>
 *   <li>{@code cancellationToken} — token checked at the start of every step.
 *       When cancelled the loop throws immediately. May be {@code null}
 *       (treated as {@link CancellationToken#noop()}).</li>
 * </ul>
 *
 * <h2>Constructors (newest → oldest)</h2>
 * All constructors are backward-compatible so existing code need not change.
 */
public record ExecutionContext(
        String sessionId,
        ConversationState conversationState,
        AgentConfig agentConfig,
        String memoryContext,
        CancellationToken cancellationToken
) {

    /** Full constructor — explicit nullability for optional fields. */
    public ExecutionContext {
        // cancellationToken may be null (loop checks for null before calling isCancelled)
    }

    /** With memory context, without cancellation token. */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig, String memoryContext) {
        this(sessionId, conversationState, agentConfig, memoryContext, null);
    }

    /** Without memory context or cancellation token (original constructor). */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig) {
        this(sessionId, conversationState, agentConfig, null, null);
    }

    /** Returns {@code true} if a cancellation has been requested. */
    public boolean isCancelled() {
        return cancellationToken != null && cancellationToken.isCancelled();
    }
}
