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

public final class AnthropicCodec implements ProviderCodec {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);
            return HttpRequest.newBuilder()
                    .uri(config.endpoint())
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .timeout(config.timeout())
                    .POST(BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Anthropic request", e);
        }
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            String stopReason = root.path("stop_reason").asText();

            if ("tool_use".equals(stopReason)) {
                for (JsonNode block : root.path("content")) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        String id = block.path("id").asText();
                        String name = block.path("name").asText();
                        String input = mapper.writeValueAsString(block.path("input"));
                        return new ToolRequest(id, name, input);
                    }
                }
                throw new RuntimeException("stop_reason=tool_use but no tool_use block found in: " + responseBody);
            }

            // Collect all text blocks as the final answer
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            return new FinalAnswer(text.toString());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response", e);
        }
    }

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokens());
        body.put("system", request.system());

        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            switch (msg.role()) {
                case USER -> addTextMessage(messages, "user", msg.content());
                case ASSISTANT -> {
                    if (msg.toolCallId() != null) {
                        addAssistantToolCall(messages, msg);
                    } else {
                        addTextMessage(messages, "assistant", msg.content());
                    }
                }
                case TOOL -> addToolResult(messages, msg);
                case SYSTEM -> { /* handled via top-level system field */ }
            }
        }

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
        // content format: "toolName|arguments"
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
