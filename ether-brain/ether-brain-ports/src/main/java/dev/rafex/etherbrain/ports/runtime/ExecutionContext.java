package dev.rafex.etherbrain.ports.runtime;

import dev.rafex.etherbrain.ports.session.ConversationState;

/**
 * Runtime context passed to the agent loop on each step.
 *
 * <p>{@code memoryContext} carries relevant past context retrieved from
 * {@link dev.rafex.etherbrain.ports.memory.MemoryProvider}. It is injected
 * into the prompt by {@link dev.rafex.etherbrain.core.prompt.PromptBuilder}
 * but is never persisted in the session store.
 */
public record ExecutionContext(
        String sessionId,
        ConversationState conversationState,
        AgentConfig agentConfig,
        String memoryContext           // null = no hay memoria disponible
) {

    /** Backward-compatible constructor — sin contexto de memoria. */
    public ExecutionContext(String sessionId, ConversationState conversationState,
                            AgentConfig agentConfig) {
        this(sessionId, conversationState, agentConfig, null);
    }
}
