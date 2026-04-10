package dev.rafex.etherbrain.ports.session;

import dev.rafex.etherbrain.ports.model.Message;
import java.util.ArrayList;
import java.util.List;

public final class ConversationState {

    private final List<Message> messages = new ArrayList<>();

    public void add(Message message) {
        messages.add(message);
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }
}
