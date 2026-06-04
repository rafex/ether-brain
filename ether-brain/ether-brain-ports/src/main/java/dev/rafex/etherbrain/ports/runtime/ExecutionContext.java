package dev.rafex.etherbrain.ports.runtime;

import dev.rafex.etherbrain.ports.session.ConversationState;

/**
 * Runtime context passed to the agent loop on each step.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code sessionId} — conversation session identifier.</li>
 *   <li>{@code memoryContext} — relevant past context retrieved from
 *       {@link dev.rafex.etherbrain.ports.memory.MemoryProvider}, injected into
 *       the prompt. Never persisted in the session store.</li>
 *   <li>{@code cancellationToken} — token checked at the start of every step.
 *       When cancelled the loop throws immediately. May be {@code null}
 *       (treated as {@link CancellationToken#noop()}).</li>
 *   <li>{@code requestId} — short correlation ID generated per HTTP request (or
 *       per {@link dev.rafex.etherbrain.core.runtime.AgentRuntime#run} call).
 *       Appears in metrics tags and log lines so a single turn can be traced
 *       end-to-end across the HTTP layer and the agent loop. May be {@code null}.</li>
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
        CancellationToken cancellationToken,
        String requestId
) {

    /** Full constructor — all fields explicit. */
    public ExecutionContext {
        // cancellationToken may be null (loop checks for null before calling isCancelled)
        // requestId may be null (metrics/logs simply omit the tag)
    }

    /** With cancellation token, without requestId. */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig, String memoryContext,
                            CancellationToken cancellationToken) {
        this(sessionId, conversationState, agentConfig, memoryContext, cancellationToken, null);
    }

    /** With memory context, without cancellation token or requestId. */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig, String memoryContext) {
        this(sessionId, conversationState, agentConfig, memoryContext, null, null);
    }

    /** Without memory context, cancellation token, or requestId (original constructor). */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig) {
        this(sessionId, conversationState, agentConfig, null, null, null);
    }

    /** Returns {@code true} if a cancellation has been requested. */
    public boolean isCancelled() {
        return cancellationToken != null && cancellationToken.isCancelled();
    }
}
