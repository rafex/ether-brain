package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    @Test
    void addsUserMessageAndRunsLoop() throws Exception {
        TestSessionStore store = new TestSessionStore();
        AgentRuntime runtime = runtimeWith(store);

        String answer = runtime.run("session-a", "hello");

        assertEquals("pong", answer);
        // Session was saved with the full conversation
        ConversationState saved = store.load("session-a");
        assertFalse(saved.messages().isEmpty());
    }

    @Test
    void continuesExistingSession() throws Exception {
        TestSessionStore store = new TestSessionStore();
        // Seed an existing session
        ConversationState existing = new ConversationState();
        existing.add(new Message(Message.Role.USER, "first turn"));
        existing.add(new Message(Message.Role.ASSISTANT, "first answer"));
        store.save("session-b", existing);

        AgentRuntime runtime = runtimeWith(store);
        runtime.run("session-b", "second turn");

        // Session now has the seeded messages plus the new turn
        ConversationState saved = store.load("session-b");
        long userMessages = saved.messages().stream()
                .filter(m -> m.role() == Message.Role.USER)
                .count();
        assertEquals(2, userMessages);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AgentRuntime runtimeWith(SessionStore store) {
        ToolRegistry emptyRegistry = new ToolRegistry() {
            @Override public Optional<dev.rafex.etherbrain.ports.tools.Tool> find(String n) {
                return Optional.empty();
            }
            @Override public Collection<dev.rafex.etherbrain.ports.tools.Tool> all() {
                return List.of();
            }
        };

        AgentLoop loop = new AgentLoop(
                request -> new FinalAnswer("pong"),
                emptyRegistry,
                new DefaultToolExecutor(emptyRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        return new AgentRuntime(store, loop,
                new AgentConfig(4, Duration.ofSeconds(5), Set.of()));
    }

    // Simple in-memory session store for tests (no infra dependency)
    private static class TestSessionStore implements SessionStore {
        private final Map<String, ConversationState> data = new HashMap<>();

        @Override
        public ConversationState load(String sessionId) {
            return data.computeIfAbsent(sessionId, k -> new ConversationState());
        }

        @Override
        public void save(String sessionId, ConversationState state) {
            data.put(sessionId, state);
        }
    }
}
