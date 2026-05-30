package dev.rafex.etherbrain.infra.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.session.ConversationState;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionStoreTest {

    @Test
    void loadsEmptyStateForNonExistentSession(@TempDir Path dir) {
        FileSessionStore store = new FileSessionStore(dir);
        ConversationState state = store.load("ghost");
        assertTrue(state.messages().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        FileSessionStore store = new FileSessionStore(dir);
        ConversationState original = new ConversationState();
        original.add(new Message(Message.Role.USER, "hello file"));
        original.add(new Message(Message.Role.ASSISTANT, "hello back", null));
        original.add(new Message(Message.Role.ASSISTANT, "tool-call|{}", "call-id-1"));
        original.add(new Message(Message.Role.TOOL, "tool result", "call-id-1"));

        store.save("session-file", original);
        ConversationState loaded = store.load("session-file");

        assertEquals(4, loaded.messages().size());
        assertEquals(Message.Role.USER, loaded.messages().get(0).role());
        assertEquals("hello file", loaded.messages().get(0).content());
        assertEquals("call-id-1", loaded.messages().get(2).toolCallId());
        assertEquals("call-id-1", loaded.messages().get(3).toolCallId());
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        // First instance saves
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "persist me"));
        new FileSessionStore(dir).save("persist-test", state);

        // Second instance loads
        ConversationState loaded = new FileSessionStore(dir).load("persist-test");
        assertEquals(1, loaded.messages().size());
        assertEquals("persist me", loaded.messages().getFirst().content());
    }

    @Test
    void multipleSessionsStoredAsSeparateFiles(@TempDir Path dir) {
        FileSessionStore store = new FileSessionStore(dir);

        ConversationState a = new ConversationState();
        a.add(new Message(Message.Role.USER, "session A"));
        store.save("a", a);

        ConversationState b = new ConversationState();
        b.add(new Message(Message.Role.USER, "session B"));
        store.save("b", b);

        assertEquals("session A", store.load("a").messages().getFirst().content());
        assertEquals("session B", store.load("b").messages().getFirst().content());
        assertTrue(dir.resolve("a.json").toFile().exists());
        assertTrue(dir.resolve("b.json").toFile().exists());
    }
}
