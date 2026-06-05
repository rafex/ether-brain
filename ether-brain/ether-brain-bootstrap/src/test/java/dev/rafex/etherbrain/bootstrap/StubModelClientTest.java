package dev.rafex.etherbrain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolDescriptor;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubModelClientTest {

    private StubModelClient client;

    @BeforeEach
    void setUp() {
        client = new StubModelClient();
    }

    @Test
    void returnsEchoToolForGenericMessage() {
        ModelRequest request = request("¿Cómo estás?");
        ModelResponse response = client.generate(request);
        assertInstanceOf(ToolRequest.class, response);
        assertEquals("echo", ((ToolRequest) response).toolName());
    }

    @Test
    void returnsCurrentTimeToolWhenMessageContainsTime() {
        ModelRequest request = request("What time is it?");
        ModelResponse response = client.generate(request);
        assertInstanceOf(ToolRequest.class, response);
        assertEquals("current_time", ((ToolRequest) response).toolName());
    }

    @Test
    void returnsCurrentTimeToolWhenMessageContainsHora() {
        ModelRequest request = request("¿Qué hora es?");
        ModelResponse response = client.generate(request);
        assertInstanceOf(ToolRequest.class, response);
        assertEquals("current_time", ((ToolRequest) response).toolName());
    }

    @Test
    void returnsFinalAnswerWhenToolResultPresent() {
        List<Message> messages = List.of(
                new Message(Message.Role.USER, "hello"),
                new Message(Message.Role.TOOL, "42", "call-1")
        );
        ModelRequest request = new ModelRequest("sys", messages, List.of());
        ModelResponse response = client.generate(request);
        assertInstanceOf(FinalAnswer.class, response);
        assertEquals("Resultado de tool: 42", ((FinalAnswer) response).content());
    }

    @Test
    void finalAnswerUsesLastToolResult() {
        List<Message> messages = List.of(
                new Message(Message.Role.USER,   "q"),
                new Message(Message.Role.TOOL,   "first",  "c1"),
                new Message(Message.Role.TOOL,   "second", "c2")
        );
        ModelRequest request = new ModelRequest("sys", messages, List.of());
        ModelResponse response = client.generate(request);
        assertInstanceOf(FinalAnswer.class, response);
        assertEquals("Resultado de tool: second", ((FinalAnswer) response).content());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ModelRequest request(String userMessage) {
        return new ModelRequest(
                "You are a test agent.",
                List.of(new Message(Message.Role.USER, userMessage)),
                List.of());
    }
}
