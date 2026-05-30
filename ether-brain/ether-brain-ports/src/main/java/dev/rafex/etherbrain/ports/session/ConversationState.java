package dev.rafex.etherbrain.ports.session;

import dev.rafex.etherbrain.ports.model.Message;
import java.util.ArrayList;
import java.util.List;

public final class ConversationState {

    private final List<Message> messages = new ArrayList<>();
    /** 0 means unlimited. */
    private final int maxMessages;

    public ConversationState() {
        this.maxMessages = 0;
    }

    public ConversationState(int maxMessages) {
        if (maxMessages < 0) {
            throw new IllegalArgumentException("maxMessages must be >= 0");
        }
        this.maxMessages = maxMessages;
    }

    public void add(Message message) {
        messages.add(message);
        if (maxMessages > 0 && messages.size() > maxMessages) {
            trim();
        }
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public int size() {
        return messages.size();
    }

    /**
     * Removes oldest messages until size <= maxMessages.
     * Keeps ASSISTANT tool-call and its paired TOOL result together so
     * provider codecs always see complete turns.
     */
    private void trim() {
        while (messages.size() > maxMessages && !messages.isEmpty()) {
            Message first = messages.getFirst();
            messages.removeFirst();

            if (first.role() == Message.Role.ASSISTANT
                    && first.toolCallId() != null
                    && !messages.isEmpty()
                    && messages.getFirst().role() == Message.Role.TOOL) {
                messages.removeFirst();
            }
        }
    }
}
