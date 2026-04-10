package dev.rafex.etherbrain.ports.runtime;

import dev.rafex.etherbrain.ports.session.ConversationState;

public record ExecutionContext(
        String sessionId,
        ConversationState conversationState,
        AgentConfig agentConfig
) {
}
