package dev.rafex.etherbrain.infra.http.codec;

import dev.rafex.etherbrain.common.MessageConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.infra.http.ProviderCodec;
import dev.rafex.etherbrain.ports.model.BatchedToolRequest;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolDescriptor;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

            // ── Tool call(s) ──────────────────────────────────────────────────
            // Claude can return multiple tool_use blocks in one turn.
            // Return all of them so AgentLoop can execute them in parallel.
            if ("tool_use".equals(stopReason)) {
                List<ToolRequest> calls = new ArrayList<>();
                for (JsonNode block : contentBlocks) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String id    = block.path("id").asText();
                        String name  = block.path("name").asText();
                        String input = mapper.writeValueAsString(block.path("input"));
                        calls.add(new ToolRequest(id, name, input));
                    }
                }
                if (calls.isEmpty()) {
                    throw new RuntimeException(
                            "stop_reason=tool_use but no tool_use block found. Body: " + responseBody);
                }
                return calls.size() == 1 ? calls.get(0) : new BatchedToolRequest(calls);
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
        if (config.temperature() >= 0) {
            body.put("temperature", config.temperature());
        }

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
        // Domain internal format: "toolNamearguments" (legacy: "toolName|arguments")
        String[] parts   = MessageConstants.splitToolCall(msg.content());
        String toolName  = parts[0];
        String arguments = parts[1];

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

    // ── Streaming ──────────────────────────────────────────────────────────────

    /**
     * Executes a streaming call using Anthropic's SSE event format ({@code "stream": true}).
     *
     * <h3>Wire format (simplified)</h3>
     * <pre>
     * event: message_start
     * data: {"type":"message_start","message":{...}}
     *
     * event: content_block_start
     * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
     *
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     *
     * event: message_delta
     * data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
     *
     * event: message_stop
     * data: {"type":"message_stop"}
     * </pre>
     * Tool calls use {@code tool_use} blocks with {@code input_json_delta} deltas.
     */
    @Override
    public ModelResponse generateStreaming(ModelRequest request, HttpModelConfig config,
                                           HttpClient httpClient,
                                           Consumer<String> onToken) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(serializeRequest(request, config));
        body.put("stream", true);
        String bodyStr = mapper.writeValueAsString(body);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(java.net.URI.create(endpoint(config)))
                .header("Content-Type",       "application/json")
                .header("x-api-key",          config.apiKey())
                .header("anthropic-version",  ANTHROPIC_VERSION)
                .timeout(config.timeout())
                .POST(BodyPublishers.ofString(bodyStr))
                .build();

        var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String errorBody = response.body()
                    .filter(l -> !l.isBlank())
                    .reduce("", (a, b) -> a + b);
            throw new RuntimeException(
                    "Anthropic returned HTTP %d: %s".formatted(response.statusCode(), errorBody));
        }

        StringBuilder fullContent = new StringBuilder();
        Map<Integer, AnthropicToolAccumulator> toolCalls = new LinkedHashMap<>();
        String stopReason = null;

        for (String line : (Iterable<String>) response.body()::iterator) {
            if (!line.startsWith("data: ")) continue;
            String data = line.substring(6).trim();

            try {
                JsonNode event = mapper.readTree(data);
                String type = event.path("type").asText();

                switch (type) {
                    case "content_block_start" -> {
                        JsonNode block = event.path("content_block");
                        if ("tool_use".equals(block.path("type").asText())) {
                            int idx = event.path("index").asInt(0);
                            AnthropicToolAccumulator acc = new AnthropicToolAccumulator();
                            acc.id   = block.path("id").asText();
                            acc.name = block.path("name").asText();
                            toolCalls.put(idx, acc);
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = event.path("delta");
                        String deltaType = delta.path("type").asText();
                        int idx = event.path("index").asInt(0);

                        if ("text_delta".equals(deltaType)) {
                            String text = delta.path("text").asText("");
                            if (!text.isEmpty()) {
                                fullContent.append(text);
                                onToken.accept(text);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            String partial = delta.path("partial_json").asText("");
                            toolCalls.computeIfAbsent(idx, k -> new AnthropicToolAccumulator())
                                     .arguments.append(partial);
                        }
                    }
                    case "message_delta" -> {
                        String sr = event.path("delta").path("stop_reason").asText(null);
                        if (sr != null && !"null".equals(sr)) stopReason = sr;
                    }
                    case "error" -> {
                        String errMsg = event.path("error").path("message").asText(data);
                        throw new RuntimeException("Anthropic streaming error: " + errMsg);
                    }
                    default -> { /* ignore ping, message_start, content_block_stop… */ }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception ignored) {
                // Malformed event — skip
            }
        }

        // ── Build final response ───────────────────────────────────────────────
        if ("tool_use".equals(stopReason) || !toolCalls.isEmpty()) {
            List<ToolRequest> calls = toolCalls.values().stream()
                    .map(acc -> new ToolRequest(
                            acc.id   != null ? acc.id   : "tool-" + System.nanoTime(),
                            acc.name != null ? acc.name : "unknown",
                            acc.arguments.length() > 0 ? acc.arguments.toString() : "{}"))
                    .toList();
            return calls.size() == 1 ? calls.get(0) : new BatchedToolRequest(calls);
        }
        return new FinalAnswer(fullContent.toString());
    }

    @Override
    public boolean supportsStreaming() { return true; }

    private static final class AnthropicToolAccumulator {
        String        id;
        String        name;
        StringBuilder arguments = new StringBuilder();
    }
}
