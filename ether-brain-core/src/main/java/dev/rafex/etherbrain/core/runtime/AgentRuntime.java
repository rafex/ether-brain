package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;

public final class AgentRuntime {

    private final SessionStore sessionStore;
    private final AgentLoop agentLoop;
    private final AgentConfig agentConfig;

    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop, AgentConfig agentConfig) {
        this.sessionStore = sessionStore;
        this.agentLoop = agentLoop;
        this.agentConfig = agentConfig;
    }

    public String run(String sessionId, String userMessage) throws Exception {
        ConversationState state = sessionStore.load(sessionId);
        state.add(new Message(Message.Role.USER, userMessage));
        String finalAnswer = agentLoop.run(new ExecutionContext(sessionId, state, agentConfig));
        sessionStore.save(sessionId, state);
        return finalAnswer;
    }
}
