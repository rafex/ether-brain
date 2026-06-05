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
 * Codec for AWS Bedrock API.
 *
 * <h2>Format notes</h2>
 * <ul>
 *   <li>Endpoint: {@code https://bedrock-runtime.{region}.amazonaws.com/model/{model-id}/invoke}</li>
 *   <li>Auth: AWS SigV4 signature (requires separate HTTP client configuration)</li>
 *   <li>Messages: depend on the underlying model (Claude, Llama, etc.)</li>
 *   <li>This codec assumes Claude-via-Bedrock format (compatible with Anthropic)</li>
 * </ul>
 *
 * <p><b>Important:</b> This codec works with pre-authenticated HTTP clients.
 * The caller must configure SigV4 signing via an HTTP interceptor or similar mechanism
 * before reaching this codec (e.g. using AWS SDK's SigV4 signer).
 */
public final class BedrockCodec implements ProviderCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);

            return HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint(config)))
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout())
                    .POST(BodyPublishers.ofString(body))
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Bedrock request", e);
        }
    }

    /**
     * Construye el endpoint completo para AWS Bedrock.
     * <pre>
     * LLM_URL=https://bedrock-runtime.us-east-1.amazonaws.com
     * LLM_MODEL=anthropic.claude-opus-4-5-20250514-v1:12000
     * → https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-opus-4-5.../invoke
     * </pre>
     */
    static String endpoint(HttpModelConfig config) {
        String base = config.endpoint().toString().replaceAll("/+$", "");
        if (base.contains("/model/")) return base;   // ya incluye el path
        try {
            String encodedModel = java.net.URLEncoder.encode(
                    config.model(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return base + "/model/" + encodedModel + "/invoke";
        } catch (Exception e) {
            return base + "/model/" + config.model() + "/invoke";
        }
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // Detect API-level error
            if (root.has("__type") || root.has("message")) {
                String msg = root.path("message").asText(
                    root.path("__type").asText(responseBody));
                throw new RuntimeException("Bedrock error: " + msg);
            }

            // Parse the response based on the model type
            // For Claude-via-Bedrock, the format is similar to Anthropic
            String stopReason = root.path("stop_reason").asText("");

            if ("tool_use".equals(stopReason)) {
                JsonNode content = root.path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_use".equals(block.path("type").asText())) {
                            String id = block.path("id").asText();
                            String name = block.path("name").asText();
                            String input = mapper.writeValueAsString(block.path("input"));
                            return new ToolRequest(id, name, input);
                        }
                    }
                }
            }

            // Collect text blocks
            StringBuilder text = new StringBuilder();
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        text.append(block.path("text").asText());
                    }
                }
            }

            return new FinalAnswer(text.toString());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Bedrock response", e);
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokens());
        if (config.temperature() >= 0) {
            body.put("temperature", config.temperature());
        }

        // System prompt as top-level field (Anthropic-style)
        if (request.system() != null && !request.system().isBlank()) {
            body.put("system", request.system());
        }

        // Messages
        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            switch (msg.role()) {
                case USER -> addTextMessage(messages, "user", msg.content());
                case SYSTEM -> { /* handled above */ }
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

        // Tools (if present)
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDescriptor tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("input_schema", mapper.readTree(tool.inputSchema()));
            }
        }

        return mapper.writeValueAsString(body);
    }

    private void addTextMessage(ArrayNode messages, String role, String content) {
        ObjectNode m = messages.addObject();
        m.put("role", role);
        m.put("content", content);
    }

    private void addAssistantToolCall(ArrayNode messages, Message msg) throws Exception {
        String[] parts = msg.content().split("\\|", 2);
        String toolName = parts[0];
        String arguments = parts.length > 1 ? parts[1] : "{}";

        ObjectNode m = messages.addObject();
        m.put("role", "assistant");
        ArrayNode content = m.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", msg.toolCallId());
        toolUse.put("name", toolName);
        toolUse.set("input", mapper.readTree(arguments));
    }

    private void addToolResult(ArrayNode messages, Message msg) {
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        ArrayNode content = m.putArray("content");
        ObjectNode result = content.addObject();
        result.put("type", "tool_result");
        result.put("tool_use_id", msg.toolCallId() != null ? msg.toolCallId() : "");
        result.put("content", msg.content());
    }
}
