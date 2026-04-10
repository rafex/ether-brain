package dev.rafex.etherbrain.ports.model;

import java.util.List;

public record ModelRequest(List<Message> messages) {

    public ModelRequest {
        messages = List.copyOf(messages);
    }
}
