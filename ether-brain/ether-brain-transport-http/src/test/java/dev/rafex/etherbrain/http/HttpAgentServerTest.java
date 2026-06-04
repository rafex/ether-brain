package dev.rafex.etherbrain.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpAgentServerTest {

    // ── extractMessage ────────────────────────────────────────────────────────

    @Test
    void extractsSimpleMessage() {
        assertEquals("¿Quién eres?", HttpAgentServer.extractMessage("{\"message\":\"¿Quién eres?\"}"));
    }

    @Test
    void extractsMessageWithSpaces() {
        assertEquals("Hola mundo", HttpAgentServer.extractMessage("{ \"message\" : \"Hola mundo\" }"));
    }

    @Test
    void extractsMessageWithEscapedQuotes() {
        assertEquals("Dice \"hola\"",
                HttpAgentServer.extractMessage("{\"message\":\"Dice \\\"hola\\\"\"}"));
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
        assertEquals("¿Qué hora es?", HttpAgentServer.extractMessage(
                "{\"session\":\"s1\",\"message\":\"¿Qué hora es?\",\"extra\":true}"));
    }

    // ── extractField ──────────────────────────────────────────────────────────

    @Test
    void extractsSessionIdField() {
        String json = "{\"session_id\":\"abc-123\",\"message\":\"hi\"}";
        assertEquals("abc-123", HttpAgentServer.extractField(json, "session_id"));
    }

    @Test
    void extractsCallbackUrlField() {
        String json = "{\"message\":\"task\",\"callback_url\":\"https://example.com/hook\"}";
        assertEquals("https://example.com/hook",
                HttpAgentServer.extractField(json, "callback_url"));
    }

    @Test
    void extractFieldReturnsNullForMissingField() {
        assertNull(HttpAgentServer.extractField("{\"a\":\"1\"}", "b"));
    }

    @Test
    void extractFieldHandlesNewlineEscape() {
        String json = "{\"message\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", HttpAgentServer.extractField(json, "message"));
    }

    // ── jsonString ────────────────────────────────────────────────────────────

    @Test
    void jsonStringEscapesNewlines() {
        String result = HttpAgentServer.jsonString("a\nb");
        assertTrue(result.contains("\\n"), "Expected escaped newline, got: " + result);
        assertFalse(result.contains("\n"), "Must not contain raw newline");
    }

    @Test
    void jsonStringEscapesQuotes() {
        String result = HttpAgentServer.jsonString("say \"hi\"");
        assertTrue(result.contains("\\\""));
    }

    @Test
    void jsonStringHandlesNull() {
        assertEquals("null", HttpAgentServer.jsonString(null));
    }

    @Test
    void jsonStringWrapsInDoubleQuotes() {
        String result = HttpAgentServer.jsonString("hello");
        assertEquals("\"hello\"", result);
    }
}
