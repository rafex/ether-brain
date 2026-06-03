package dev.rafex.etherbrain.infra.http.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.infra.http.ProviderCodec;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolDescriptor;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;

/**
 * Codec for the Anthropic Messages API (Claude models).
 *
 * <h2>Differences from OpenAI format</h2>
 * <ul>
 *   <li>{@code system} is a top-level field, not a message.</li>
 *   <li>Tool calls are returned as {@code content} blocks of type {@code tool_use}.</li>
 *   <li>Tool results are sent as {@code user} messages with {@code tool_result} content blocks.</li>
 *   <li>Tool schemas use {@code input_schema} (not {@code parameters}).</li>
 *   <li>Auth header: {@code x-api-key} (not {@code Authorization: Bearer}).</li>
 * </ul>
 *
 * <h2>Text before tool_use</h2>
 * Claude sometimes emits a {@code text} block before the {@code tool_use} block.
 * When {@code stop_reason=tool_use}, this codec returns the {@link ToolRequest} and
 * accumulates any preceding text into the tool name field prefix so it is not lost
 * if the caller needs to inspect it (though the domain typically discards it).
 */
public final class AnthropicCodec implements ProviderCodec {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Path que este codec añade a la URL base del proveedor. */
    private static final String API_PATH = "/v1/messages";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint(config)))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .timeout(config.timeout());

            config.extraHeaders().forEach(builder::header);

            return builder.POST(BodyPublishers.ofString(body)).build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Anthropic request", e);
        }
    }

    /**
     * Construye el endpoint completo a partir de la URL base del proveedor.
     * <pre>
     * https://api.anthropic.com        → .../v1/messages
     * https://api.anthropic.com/v1     → .../v1/messages   (sin doble /v1)
     * https://api.anthropic.com/v1/messages → tal cual (retrocompat)
     * </pre>
     */
    static String endpoint(HttpModelConfig config) {
        String base = config.endpoint().toString().replaceAll("/+$", "");
        if (base.contains("/messages"))  return base;
        if (base.endsWith("/v1"))        return base + "/messages";
        return base + API_PATH;
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // ── Detect API-level error ────────────────────────────────────────
            // Anthropic error format: {"type":"error","error":{"type":"...","message":"..."}}
            if ("error".equals(root.path("type").asText())) {
                JsonNode err = root.path("error");
                String errType = err.path("type").asText("");
                String errMsg  = err.path("message").asText(responseBody);
                throw new RuntimeException("Anthropic error" +
                        (errType.isBlank() ? "" : " [" + errType + "]") + ": " + errMsg);
            }

            String stopReason = root.path("stop_reason").asText();
            JsonNode contentBlocks = root.path("content");

            // ── Tool call ─────────────────────────────────────────────────────
            if ("tool_use".equals(stopReason)) {
                for (JsonNode block : contentBlocks) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String id    = block.path("id").asText();
                        String name  = block.path("name").asText();
                        String input = mapper.writeValueAsString(block.path("input"));
                        return new ToolRequest(id, name, input);
                    }
                }
                throw new RuntimeException(
                        "stop_reason=tool_use but no tool_use block found. Body: " + responseBody);
            }

            // ── Final answer — collect all text blocks ────────────────────────
            StringBuilder text = new StringBuilder();
            for (JsonNode block : contentBlocks) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }

            // Guard: if no text blocks were found but the response looks valid, return empty
            return new FinalAnswer(text.toString());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response", e);
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model",      config.model());
        body.put("max_tokens", config.maxTokens());

        // System is a top-level field in Anthropic's API
        if (request.system() != null && !request.system().isBlank()) {
            body.put("system", request.system());
        }

        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            switch (msg.role()) {
                case USER    -> addTextMessage(messages, "user", msg.content());
                case SYSTEM  -> { /* handled via top-level system field */ }
                case ASSISTANT -> {
                    if (msg.toolCallId() != null) {
                        addAssistantToolCall(messages, msg);
                    } else {
                        addTextMessage(messages, "assistant", msg.content());
                    }
                }
                case TOOL -> addToolResult(messages, msg);
            }
        }

        // Only include tools when there are actual tools to expose
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDescriptor tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name",        tool.name());
                t.put("description", tool.description());
                t.set("input_schema", mapper.readTree(tool.inputSchema()));
            }
        }

        return mapper.writeValueAsString(body);
    }

    private void addTextMessage(ArrayNode messages, String role, String content) {
        ObjectNode m = messages.addObject();
        m.put("role",    role);
        m.put("content", content);
    }

    private void addAssistantToolCall(ArrayNode messages, Message msg) throws Exception {
        // Domain internal format: "toolName|arguments"
        String[] parts    = msg.content().split("\\|", 2);
        String toolName   = parts[0];
        String arguments  = parts.length > 1 ? parts[1] : "{}";

        ObjectNode m = messages.addObject();
        m.put("role", "assistant");
        ArrayNode content = m.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id",   msg.toolCallId());
        toolUse.put("name", toolName);
        toolUse.set("input", mapper.readTree(arguments));
    }

    private void addToolResult(ArrayNode messages, Message msg) {
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        ArrayNode content = m.putArray("content");
        ObjectNode result = content.addObject();
        result.put("type",        "tool_result");
        result.put("tool_use_id", msg.toolCallId() != null ? msg.toolCallId() : "");
        result.put("content",     msg.content());
    }
}
