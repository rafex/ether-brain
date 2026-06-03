package dev.rafex.etherbrain.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HttpAgentServerTest {

    // ── extractMessage ────────────────────────────────────────────────────────

    @Test
    void extractsSimpleMessage() {
        String json = "{\"message\":\"¿Quién eres?\"}";
        assertEquals("¿Quién eres?", HttpAgentServer.extractMessage(json));
    }

    @Test
    void extractsMessageWithSpaces() {
        String json = "{ \"message\" : \"Hola mundo\" }";
        assertEquals("Hola mundo", HttpAgentServer.extractMessage(json));
    }

    @Test
    void extractsMessageWithEscapedQuotes() {
        String json = "{\"message\":\"Dice \\\"hola\\\"\"}";
        assertEquals("Dice \"hola\"", HttpAgentServer.extractMessage(json));
    }

    @Test
    void returnsNullWhenMessageMissing() {
        assertNull(HttpAgentServer.extractMessage("{\"other\":\"value\"}"));
    }

    @Test
    void returnsNullForEmptyBody() {
        assertNull(HttpAgentServer.extractMessage(""));
        assertNull(HttpAgentServer.extractMessage(null));
    }

    @Test
    void extractsMessageWithOtherFields() {
        String json = "{\"session\":\"s1\",\"message\":\"¿Qué hora es?\",\"extra\":true}";
        assertEquals("¿Qué hora es?", HttpAgentServer.extractMessage(json));
    }
}
