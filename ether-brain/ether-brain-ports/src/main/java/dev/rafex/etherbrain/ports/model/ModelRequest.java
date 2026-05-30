package dev.rafex.etherbrain.ports.model;

import java.util.List;

public record ModelRequest(String system, List<Message> messages, List<ToolDescriptor> tools) {

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
