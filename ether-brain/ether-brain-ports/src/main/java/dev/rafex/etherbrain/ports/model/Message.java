package dev.rafex.etherbrain.ports.model;

public record Message(Role role, String content, String toolCallId) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public Message(Role role, String content) {
        this(role, content, null);
    }
}
