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
 * Works with OpenAI and any OpenAI-compatible endpoint (local LLMs, Azure OpenAI, etc.).
 */
public final class OpenAiCodec implements ProviderCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);
            return HttpRequest.newBuilder()
                    .uri(config.endpoint())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(config.timeout())
                    .POST(BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAI request", e);
        }
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choice = root.path("choices").get(0);
            String finishReason = choice.path("finish_reason").asText();
            JsonNode message = choice.path("message");

            if ("tool_calls".equals(finishReason)) {
                JsonNode first = message.path("tool_calls").get(0);
                String id = first.path("id").asText();
                String name = first.path("function").path("name").asText();
                String arguments = first.path("function").path("arguments").asText();
                return new ToolRequest(id, name, arguments);
            }

            return new FinalAnswer(message.path("content").asText());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI response", e);
        }
    }

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());

        ArrayNode messages = body.putArray("messages");

        // System goes as the first message
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", request.system());

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
                case TOOL -> {
                    ObjectNode m = messages.addObject();
                    m.put("role", "tool");
                    m.put("tool_call_id", msg.toolCallId() != null ? msg.toolCallId() : "");
                    m.put("content", msg.content());
                }
                case SYSTEM -> { /* already added above */ }
            }
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDescriptor tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", mapper.readTree(tool.inputSchema()));
            }
            body.put("tool_choice", "auto");
        }

        return mapper.writeValueAsString(body);
    }

    private void addTextMessage(ArrayNode messages, String role, String content) {
        ObjectNode m = messages.addObject();
        m.put("role", role);
        m.put("content", content);
    }

    private void addAssistantToolCall(ArrayNode messages, Message msg) {
        // content format: "toolName|arguments"
        String[] parts = msg.content().split("\\|", 2);
        String toolName = parts[0];
        String arguments = parts.length > 1 ? parts[1] : "{}";

        ObjectNode m = messages.addObject();
        m.put("role", "assistant");
        m.putNull("content");
        ArrayNode toolCalls = m.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id", msg.toolCallId());
        tc.put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name", toolName);
        fn.put("arguments", arguments);
    }
}
