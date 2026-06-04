package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
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

    // ── Existing tests ────────────────────────────────────────────────────────

    @Test
    void addsUserMessageAndRunsLoop() throws Exception {
        TestSessionStore store = new TestSessionStore();
        AgentRuntime runtime = runtimeWith(store);

        String answer = runtime.run("session-a", "hello");

        assertEquals("pong", answer);
        ConversationState saved = store.load("session-a");
        assertFalse(saved.messages().isEmpty());
    }

    @Test
    void continuesExistingSession() throws Exception {
        TestSessionStore store = new TestSessionStore();
        ConversationState existing = new ConversationState();
        existing.add(new Message(Message.Role.USER, "first turn"));
        existing.add(new Message(Message.Role.ASSISTANT, "first answer"));
        store.save("session-b", existing);

        AgentRuntime runtime = runtimeWith(store);
        runtime.run("session-b", "second turn");

        ConversationState saved = store.load("session-b");
        long userMessages = saved.messages().stream()
                .filter(m -> m.role() == Message.Role.USER)
                .count();
        assertEquals(2, userMessages);
    }

    // ── AgentRunner interface ─────────────────────────────────────────────────

    @Test
    void agentNameDefaultsToAgent() {
        AgentRuntime runtime = runtimeWith(new TestSessionStore());
        assertEquals("agent", runtime.agentName());
    }

    @Test
    void agentNameAndDescriptionAreConfigurable() {
        AgentRuntime runtime = namedRuntime("researcher", "Searches and synthesises information.");
        assertEquals("researcher",                         runtime.agentName());
        assertEquals("Searches and synthesises information.", runtime.agentDescription());
    }

    @Test
    void runWithCancellationTokenDelegates() throws Exception {
        TestSessionStore store = new TestSessionStore();
        AgentRuntime runtime = runtimeWith(store);
        CancellationToken token = CancellationToken.noop();

        // noop token — should run normally
        String answer = runtime.run("session-token", "hi", token);
        assertEquals("pong", answer);
    }

    @Test
    void runWithCancelledTokenThrows() {
        TestSessionStore store = new TestSessionStore();
        AgentRuntime runtime = runtimeWith(store);

        CancellationToken.Mutable token = CancellationToken.create();
        token.cancel();

        assertThrows(AgentException.class, () ->
                runtime.run("session-cancelled", "anything", token));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentRuntime runtimeWith(SessionStore store) {
        ToolRegistry emptyRegistry = emptyRegistry();
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

    private static AgentRuntime namedRuntime(String name, String desc) {
        ToolRegistry emptyRegistry = emptyRegistry();
        AgentLoop loop = new AgentLoop(
                request -> new FinalAnswer("ok"),
                emptyRegistry,
                new DefaultToolExecutor(emptyRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );
        return new AgentRuntime(new TestSessionStore(), loop,
                new AgentConfig(4, Duration.ofSeconds(5), Set.of()),
                null, name, desc);
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry() {
            @Override public Optional<dev.rafex.etherbrain.ports.tools.Tool> find(String n) {
                return Optional.empty();
            }
            @Override public Collection<dev.rafex.etherbrain.ports.tools.Tool> all() {
                return List.of();
            }
        };
    }

    private static class TestSessionStore implements SessionStore {
        private final Map<String, ConversationState> data = new HashMap<>();

        @Override public ConversationState load(String id) {
            return data.computeIfAbsent(id, k -> new ConversationState());
        }
        @Override public void save(String id, ConversationState state) {
            data.put(id, state);
        }
    }
}
