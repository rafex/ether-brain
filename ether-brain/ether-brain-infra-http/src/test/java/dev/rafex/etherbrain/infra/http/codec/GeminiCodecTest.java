package dev.rafex.etherbrain.infra.http.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import java.net.URI;
import org.junit.jupiter.api.Test;

class GeminiCodecTest {

    private static HttpModelConfig config(String baseUrl, String model) {
        return new HttpModelConfig(URI.create(baseUrl), "test-key", model,
                4096, java.time.Duration.ofSeconds(30));
    }

    // ── endpoint() ────────────────────────────────────────────────────────────

    @Test
    void endpointFromBaseUrl() {
        String url = GeminiCodec.endpoint(config("https://generativelanguage.googleapis.com", "gemini-2.0-flash"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent", url);
    }

    @Test
    void endpointAlreadyFull() {
        String full = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
        String url = GeminiCodec.endpoint(config(full, "gemini-2.0-flash"));
        assertEquals(full, url);
    }

    @Test
    void endpointStripsTrailingSlash() {
        String url = GeminiCodec.endpoint(config("https://generativelanguage.googleapis.com/", "gemini-1.5-pro"));
        assertTrue(url.contains(":generateContent"));
        assertTrue(url.contains("gemini-1.5-pro"));
    }

    // ── parseResponse() — texto ───────────────────────────────────────────────

    @Test
    void parsesFinalAnswer() {
        String body = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{"text": "Hola, soy Gemini."}],
                      "role": "model"
                    },
                    "finishReason": "STOP"
                  }]
                }
                """;
        var codec = new GeminiCodec();
        var response = codec.parseResponse(body);
        assertInstanceOf(FinalAnswer.class, response);
        assertEquals("Hola, soy Gemini.", ((FinalAnswer) response).content());
    }

    @Test
    void parsesFinalAnswerMultiPart() {
        String body = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{"text": "Parte 1. "}, {"text": "Parte 2."}],
                      "role": "model"
                    },
                    "finishReason": "STOP"
                  }]
                }
                """;
        var response = new GeminiCodec().parseResponse(body);
        assertInstanceOf(FinalAnswer.class, response);
        assertEquals("Parte 1. Parte 2.", ((FinalAnswer) response).content());
    }

    // ── parseResponse() — tool call ───────────────────────────────────────────

    @Test
    void parsesToolCall() {
        String body = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "functionCall": {
                          "name": "current_time",
                          "args": {}
                        }
                      }],
                      "role": "model"
                    },
                    "finishReason": "STOP"
                  }]
                }
                """;
        var response = new GeminiCodec().parseResponse(body);
        assertInstanceOf(ToolRequest.class, response);
        var tr = (ToolRequest) response;
        assertEquals("current_time", tr.toolName());
        assertTrue(tr.toolCallId().startsWith("gemini-"));
    }

    // ── parseResponse() — error ───────────────────────────────────────────────

    @Test
    void throwsOnApiError() {
        String body = """
                {
                  "error": {
                    "code": 400,
                    "message": "API key not valid.",
                    "status": "INVALID_ARGUMENT"
                  }
                }
                """;
        var ex = assertThrows(RuntimeException.class, () -> new GeminiCodec().parseResponse(body));
        assertTrue(ex.getMessage().contains("INVALID_ARGUMENT"));
        assertTrue(ex.getMessage().contains("API key not valid"));
    }
}
