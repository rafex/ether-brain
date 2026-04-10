package dev.rafex.etherbrain.ports.session;

public interface SessionStore {

    ConversationState load(String sessionId);

    void save(String sessionId, ConversationState state);
}
