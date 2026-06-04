package dev.rafex.etherbrain.infra.http.codec;

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
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible codec. Works with any provider that exposes
 * {@code /v1/chat/completions}: OpenAI, Groq, Deepseek, Mistral, Qwen,
 * OpenRouter, Together AI, Ollama, LM Studio, vLLM, Fireworks, Perplexity…
 *
 * <h2>Compatibility notes</h2>
 * <h2>Endpoint</h2>
 * <p>{@code LLM_URL} debe ser la URL base del proveedor. El codec añade
 * {@code /v1/chat/completions} automáticamente:
 * <pre>
 * LLM_URL=https://api.cerebras.ai  →  https://api.cerebras.ai/v1/chat/completions
 * LLM_URL=https://api.groq.com     →  https://api.groq.com/v1/chat/completions
 * LLM_URL=http://localhost:11434   →  http://localhost:11434/v1/chat/completions
 * </pre>
 * <p>Si la URL ya incluye el path completo se usa tal cual (retrocompatibilidad).
 *
 * <h2>Compatibilidad</h2>
 * <ul>
 *   <li>Tool calls: detectado por {@code finish_reason="tool_calls"} o por presencia
 *       de {@code tool_calls} no vacío (maneja proveedores que devuelven {@code "stop"}).</li>
 *   <li>Formato legacy {@code function_call}: soportado como fallback.</li>
 *   <li>{@code arguments}: normalizado tanto si llega como string o como objeto JSON.</li>
 *   <li>Headers extra (ej. {@code HTTP-Referer} de OpenRouter): via
 *       {@link HttpModelConfig#withExtraHeaders}.</li>
 * </ul>
 */
public final class OpenAiCodec implements ProviderCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Path que este codec añade a la URL base del proveedor. */
    private static final String API_PATH = "/v1/chat/completions";

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint(config)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(config.timeout());

            config.extraHeaders().forEach(builder::header);

            return builder.POST(BodyPublishers.ofString(body)).build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAI-compatible request", e);
        }
    }

    /**
     * Construye el endpoint completo a partir de la URL base del proveedor.
     *
     * <p>Casos manejados:
     * <pre>
     * https://api.cerebras.ai              → .../v1/chat/completions
     * https://api.cerebras.ai/v1           → .../v1/chat/completions   (sin doble /v1)
     * https://api.cerebras.ai/v1/chat/completions → tal cual (retrocompat)
     * https://my-proxy.com/openai          → .../openai/v1/chat/completions
     * </pre>
     */
    static String endpoint(HttpModelConfig config) {
        String base = config.endpoint().toString().replaceAll("/+$", "");
        // Ya tiene el path completo
        if (base.contains("/chat/completions")) return base;
        // Termina en /v1 — solo añadir el sufijo sin duplicar la versión
        if (base.endsWith("/v1")) return base + "/chat/completions";
        // URL base pura — añadir el path completo
        return base + API_PATH;
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // ── Detect API-level error ────────────────────────────────────────
            if (root.has("error")) {
                JsonNode err = root.path("error");
                String msg = err.path("message").asText(responseBody);
                String type = err.path("type").asText("");
                throw new RuntimeException("Provider error" +
                        (type.isBlank() ? "" : " [" + type + "]") + ": " + msg);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("No choices in response: " + responseBody);
            }

            JsonNode choice = choices.get(0);
            String finishReason = choice.path("finish_reason").asText("");
            JsonNode message = choice.path("message");

            // ── Tool call detection ───────────────────────────────────────────
            // Some providers return finish_reason="stop" even when tool_calls is present,
            // so we check both the finish_reason AND the actual tool_calls node.
            JsonNode toolCallsNode = message.path("tool_calls");
            boolean hasToolCalls = "tool_calls".equals(finishReason)
                    || (toolCallsNode.isArray() && !toolCallsNode.isEmpty());

            if (hasToolCalls && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                // Return all tool calls so AgentLoop can execute them in parallel
                List<ToolRequest> calls = new ArrayList<>();
                for (JsonNode tc : toolCallsNode) calls.add(parseToolCall(tc));
                return calls.size() == 1 ? calls.get(0) : new BatchedToolRequest(calls);
            }

            // ── Legacy function_call format (old OpenAI API) ──────────────────
            JsonNode funcCallNode = message.path("function_call");
            if (!funcCallNode.isMissingNode() && !funcCallNode.isNull()) {
                String name = funcCallNode.path("name").asText();
                String args = normalizeArguments(funcCallNode.path("arguments"));
                // Legacy format has no call ID; generate one so history stays consistent
                return new ToolRequest("fc-" + name, name, args);
            }

            // ── Final answer ──────────────────────────────────────────────────
            // content can be a string or an array of content blocks (less common in OAI format)
            JsonNode contentNode = message.path("content");
            String content;
            if (contentNode.isTextual()) {
                content = contentNode.asText();
            } else if (contentNode.isArray()) {
                // Collect text blocks (some providers use OAI-style content arrays)
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentNode) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                content = sb.toString();
            } else {
                content = contentNode.asText("");
            }

            return new FinalAnswer(content);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI-compatible response", e);
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokens());     // required by most providers

        ArrayNode messages = body.putArray("messages");

        // System prompt as first message (standard for OpenAI-compatible APIs)
        if (request.system() != null && !request.system().isBlank()) {
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", request.system());
        }

        for (Message msg : request.messages()) {
            switch (msg.role()) {
                case USER    -> addTextMessage(messages, "user", msg.content());
                case SYSTEM  -> { /* already added above */ }
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
            }
        }

        // Only add tools block when there are actual tools
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
        // Domain internal format: "toolName|arguments"
        String[] parts = msg.content().split("\\|", 2);
        String toolName  = parts[0];
        String arguments = parts.length > 1 ? parts[1] : "{}";

        ObjectNode m = messages.addObject();
        m.put("role", "assistant");
        m.putNull("content");               // null is the standard; some providers accept ""
        ArrayNode toolCalls = m.putArray("tool_calls");
        ObjectNode tc = toolCalls.addObject();
        tc.put("id",   msg.toolCallId());
        tc.put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name",      toolName);
        fn.put("arguments", arguments);     // always sent as string per OpenAI spec
    }

    // ── Parsing helpers ────────────────────────────────────────────────────────

    private ToolRequest parseToolCall(JsonNode toolCallNode) throws Exception {
        String id   = toolCallNode.path("id").asText("call-" + System.nanoTime());
        String name = toolCallNode.path("function").path("name").asText();
        String args = normalizeArguments(toolCallNode.path("function").path("arguments"));
        return new ToolRequest(id, name, args);
    }

    /**
     * Normalizes the arguments field: some providers return it as a JSON string
     * (per spec), others return it as an inline JSON object. Both are accepted here.
     */
    private String normalizeArguments(JsonNode argsNode) throws Exception {
        if (argsNode.isTextual()) {
            return argsNode.asText();   // already a JSON string — pass through
        }
        if (argsNode.isMissingNode() || argsNode.isNull()) {
            return "{}";
        }
        return mapper.writeValueAsString(argsNode);   // object → serialize to string
    }
}
