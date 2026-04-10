package dev.rafex.etherbrain.core.policy;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.ports.policy.PolicyEngine;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;

public final class DefaultPolicyEngine implements PolicyEngine {

    @Override
    public void checkBeforeStep(ExecutionContext context, int step) {
        if (step >= context.agentConfig().maxSteps()) {
            throw new AgentException("Max steps exceeded: " + context.agentConfig().maxSteps());
        }
    }

    @Override
    public void checkAfterStep(ExecutionContext context, int step) {
        if (context.conversationState().messages().size() > 100) {
            throw new AgentException("Conversation history limit exceeded");
        }
    }
}
