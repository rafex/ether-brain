package dev.rafex.etherbrain.ports.model;

public record Message(Role role, String content) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }
}
