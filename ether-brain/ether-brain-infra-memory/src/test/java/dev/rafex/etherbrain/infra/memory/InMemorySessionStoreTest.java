package dev.rafex.etherbrain.infra.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.session.ConversationState;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void loadReturnsEmptyStateForNewSession() {
        ConversationState state = store.load("new-session");
        assertTrue(state.messages().isEmpty());
    }

    @Test
    void saveAndLoadPreservesMessages() {
        ConversationState original = new ConversationState();
        original.add(new Message(Message.Role.USER, "hello"));
        original.add(new Message(Message.Role.ASSISTANT, "world"));

        store.save("s1", original);
        ConversationState loaded = store.load("s1");

        assertEquals(2, loaded.messages().size());
        assertEquals("hello", loaded.messages().get(0).content());
        assertEquals("world", loaded.messages().get(1).content());
    }

    @Test
    void multipleSessionsAreIndependent() {
        ConversationState stateA = new ConversationState();
        stateA.add(new Message(Message.Role.USER, "from A"));
        store.save("session-a", stateA);

        ConversationState stateB = new ConversationState();
        stateB.add(new Message(Message.Role.USER, "from B"));
        store.save("session-b", stateB);

        assertEquals("from A", store.load("session-a").messages().getFirst().content());
        assertEquals("from B", store.load("session-b").messages().getFirst().content());
    }

    @Test
    void loadCreatesNewStateIfNotPreviouslySaved() {
        ConversationState state1 = store.load("fresh");
        ConversationState state2 = store.load("fresh");
        // Same instance for same session (computeIfAbsent semantics)
        assertTrue(state1.messages().isEmpty());
        assertTrue(state2.messages().isEmpty());
    }
}
