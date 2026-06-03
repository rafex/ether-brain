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

class BedrockCodecTest {

    private static HttpModelConfig config(String baseUrl, String model) {
        return new HttpModelConfig(URI.create(baseUrl), "", model,
                4096, java.time.Duration.ofSeconds(30));
    }

    // ── endpoint() ────────────────────────────────────────────────────────────

    @Test
    void endpointFromBaseUrl() {
        String url = BedrockCodec.endpoint(
                config("https://bedrock-runtime.us-east-1.amazonaws.com",
                        "anthropic.claude-3-5-sonnet-20241022-v2:0"));
        assertTrue(url.startsWith("https://bedrock-runtime.us-east-1.amazonaws.com/model/"));
        assertTrue(url.endsWith("/invoke"));
    }

    @Test
    void endpointAlreadyHasModelPath() {
        String full = "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude/invoke";
        String url = BedrockCodec.endpoint(config(full, "anything"));
        assertEquals(full, url);
    }

    @Test
    void endpointStripsTrailingSlash() {
        String url = BedrockCodec.endpoint(
                config("https://bedrock-runtime.us-east-1.amazonaws.com/",
                        "meta.llama3-70b-instruct-v1:0"));
        assertTrue(url.contains("/model/"));
        assertTrue(url.endsWith("/invoke"));
    }

    // ── parseResponse() — texto ───────────────────────────────────────────────

    @Test
    void parsesFinalAnswer() {
        String body = """
                {
                  "content": [{"type": "text", "text": "Respuesta de Bedrock."}],
                  "stop_reason": "end_turn"
                }
                """;
        var response = new BedrockCodec().parseResponse(body);
        assertInstanceOf(FinalAnswer.class, response);
        assertEquals("Respuesta de Bedrock.", ((FinalAnswer) response).content());
    }

    // ── parseResponse() — tool call ───────────────────────────────────────────

    @Test
    void parsesToolCall() {
        String body = """
                {
                  "content": [
                    {"type": "text",     "text": "Voy a consultar la hora."},
                    {"type": "tool_use", "id": "tu-abc123", "name": "current_time", "input": {}}
                  ],
                  "stop_reason": "tool_use"
                }
                """;
        var response = new BedrockCodec().parseResponse(body);
        assertInstanceOf(ToolRequest.class, response);
        var tr = (ToolRequest) response;
        assertEquals("current_time", tr.toolName());
        assertEquals("tu-abc123", tr.toolCallId());
    }

    // ── parseResponse() — error ───────────────────────────────────────────────

    @Test
    void throwsOnApiError() {
        String body = """
                {
                  "__type": "ValidationException",
                  "message": "The model ID is invalid."
                }
                """;
        var ex = assertThrows(RuntimeException.class, () -> new BedrockCodec().parseResponse(body));
        assertTrue(ex.getMessage().contains("The model ID is invalid"));
    }
}
