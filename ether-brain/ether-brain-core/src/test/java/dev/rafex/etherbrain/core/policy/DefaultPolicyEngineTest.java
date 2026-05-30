package dev.rafex.etherbrain.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPolicyEngineTest {

    private final DefaultPolicyEngine policy = new DefaultPolicyEngine();

    @Test
    void checkBeforeStepThrowsWhenStepAtMax() {
        ExecutionContext ctx = context(3);
        assertThrows(AgentException.class, () -> policy.checkBeforeStep(ctx, 3));
    }

    @Test
    void checkBeforeStepPassesWhenStepBelowMax() {
        ExecutionContext ctx = context(3);
        assertDoesNotThrow(() -> policy.checkBeforeStep(ctx, 2));
    }

    @Test
    void checkAfterStepThrowsWhenHistoryExceedsLimit() {
        ConversationState state = new ConversationState();
        for (int i = 0; i < 101; i++) {
            state.add(new Message(Message.Role.USER, "msg " + i));
        }
        ExecutionContext ctx = new ExecutionContext("s1", state,
                new AgentConfig(10, Duration.ofSeconds(5), Set.of()));
        assertThrows(AgentException.class, () -> policy.checkAfterStep(ctx, 0));
    }

    @Test
    void checkAfterStepPassesWhenHistoryAtLimit() {
        ConversationState state = new ConversationState();
        for (int i = 0; i < 100; i++) {
            state.add(new Message(Message.Role.USER, "msg " + i));
        }
        ExecutionContext ctx = new ExecutionContext("s1", state,
                new AgentConfig(10, Duration.ofSeconds(5), Set.of()));
        assertDoesNotThrow(() -> policy.checkAfterStep(ctx, 0));
    }

    private ExecutionContext context(int maxSteps) {
        ConversationState state = new ConversationState();
        return new ExecutionContext("s1", state,
                new AgentConfig(maxSteps, Duration.ofSeconds(5), Set.of()));
    }
}
