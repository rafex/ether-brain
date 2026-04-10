package dev.rafex.etherbrain.infra.memory;

import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySessionStore implements SessionStore {

    private final Map<String, ConversationState> sessions = new ConcurrentHashMap<>();

    @Override
    public ConversationState load(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ignored -> new ConversationState());
    }

    @Override
    public void save(String sessionId, ConversationState state) {
        sessions.put(sessionId, state);
    }
}
