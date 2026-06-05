package dev.rafex.etherbrain.infra.http.codec;

import dev.rafex.etherbrain.common.MessageConstants;
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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Codec for Google Gemini API (generativelanguage.googleapis.com).
 *
 * <h2>Diferencias clave vs OpenAI</h2>
 * <ul>
 *   <li>Auth: API key como query param {@code ?key=...}, no header Bearer.</li>
 *   <li>System prompt: campo top-level {@code systemInstruction}, no un mensaje.</li>
 *   <li>Mensajes: array {@code contents} con {@code role=user|model} y array {@code parts}.</li>
 *   <li>Tool calls: partes de tipo {@code functionCall} dentro de {@code parts}.</li>
 *   <li>Tool results: partes de tipo {@code functionResponse} en un turno {@code user}.</li>
 *   <li>No existe un ID de llamada — el nombre de la función es el enlace entre call y result.</li>
 * </ul>
 *
 * <h2>Configuración</h2>
 * <pre>{@code
 * LLM_TYPE=gemini
 * LLM_URL=https://generativelanguage.googleapis.com   ← solo la URL base
 * LLM_TOKEN=AIzaSy...
 * LLM_MODEL=gemini-2.0-flash
 * }</pre>
 * <p>El codec construye automáticamente:
 * {@code {base}/v1beta/models/{model}:generateContent?key={token}}
 */
public final class GeminiCodec implements ProviderCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config) {
        try {
            String body = serializeRequest(request, config);

            // Gemini auth: API key as query parameter
            String url = endpoint(config) + "?key=" +
                    URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout());

            config.extraHeaders().forEach(builder::header);

            return builder.POST(BodyPublishers.ofString(body)).build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Gemini request", e);
        }
    }

    /**
     * Construye el endpoint completo de Gemini.
     * <pre>
     * LLM_URL=https://generativelanguage.googleapis.com
     * LLM_MODEL=gemini-2.0-flash
     * → https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
     * </pre>
     * Si la URL ya incluye {@code :generateContent} se usa tal cual.
     */
    static String endpoint(HttpModelConfig config) {
        String base = config.endpoint().toString().replaceAll("/+$", "");
        if (base.contains(":generateContent")) return base;
        return base + "/v1beta/models/" + config.model() + ":generateContent";
    }

    @Override
    public ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // ── Error de API ──────────────────────────────────────────────────
            if (root.has("error")) {
                JsonNode err = root.path("error");
                String msg = err.path("message").asText(responseBody);
                String status = err.path("status").asText("");
                throw new RuntimeException("Gemini error" +
                        (status.isBlank() ? "" : " [" + status + "]") + ": " + msg);
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new RuntimeException("No candidates in Gemini response: " + responseBody);
            }

            JsonNode candidate = candidates.get(0);
            JsonNode parts = candidate.path("content").path("parts");

            // ── Tool call: cualquier part que tenga "functionCall" ─────────────
            for (JsonNode part : parts) {
                JsonNode funcCallNode = part.path("functionCall");
                if (!funcCallNode.isMissingNode() && !funcCallNode.isNull()) {
                    String name = funcCallNode.path("name").asText();
                    JsonNode argsNode = funcCallNode.path("args");
                    String args = argsNode.isMissingNode()
                            ? "{}"
                            : mapper.writeValueAsString(argsNode);
                    // Gemini no tiene call ID — usamos "gemini-{name}" para poder
                    // enlazar el resultado en el historial
                    return new ToolRequest("gemini-" + name, name, args);
                }
            }

            // ── Respuesta de texto: concatenar todos los parts de texto ────────
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    text.append(part.path("text").asText());
                }
            }

            return new FinalAnswer(text.toString());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response", e);
        }
    }

    // ── Serialización ─────────────────────────────────────────────────────────

    private String serializeRequest(ModelRequest request, HttpModelConfig config) throws Exception {
        ObjectNode body = mapper.createObjectNode();

        // System prompt: campo top-level en Gemini (no un mensaje)
        if (request.system() != null && !request.system().isBlank()) {
            ObjectNode sysInstruction = body.putObject("systemInstruction");
            sysInstruction.putArray("parts")
                    .addObject()
                    .put("text", request.system());
        }

        // Historial de conversación
        ArrayNode contents = body.putArray("contents");
        String lastToolName = null;   // Gemini no tiene ID — rastreamos el nombre

        for (Message msg : request.messages()) {
            switch (msg.role()) {
                case USER    -> addUserText(contents, msg.content());
                case SYSTEM  -> { /* ya en systemInstruction */ }
                case ASSISTANT -> {
                    if (msg.toolCallId() != null) {
                        // "toolNamearguments" (legacy: "toolName|arguments")
                        String[] split = MessageConstants.splitToolCall(msg.content());
                        lastToolName = split[0];
                        String arguments = split[1];
                        addModelFunctionCall(contents, lastToolName, arguments);
                    } else {
                        lastToolName = null;
                        addModelText(contents, msg.content());
                    }
                }
                case TOOL -> {
                    // El nombre de la función viene del turno ASSISTANT anterior
                    String funcName = extractFuncName(msg.toolCallId(), lastToolName);
                    addFunctionResponse(contents, funcName, msg.content());
                    lastToolName = null;
                }
            }
        }

        // Tools: functionDeclarations
        if (!request.tools().isEmpty()) {
            ObjectNode toolsNode = body.putObject("tools");
            ArrayNode funcDecls = toolsNode.putArray("functionDeclarations");
            for (ToolDescriptor tool : request.tools()) {
                ObjectNode decl = funcDecls.addObject();
                decl.put("name", tool.name());
                decl.put("description", tool.description());
                decl.set("parameters", mapper.readTree(tool.inputSchema()));
            }
        }

        // Config de generación
        ObjectNode genConfig = body.putObject("generationConfig")
                .put("maxOutputTokens", config.maxTokens());
        // temperature: usa el valor configurado; si no está definido, usa 0.7 (default Gemini)
        genConfig.put("temperature",
                config.temperature() >= 0 ? config.temperature() : 0.7);

        return mapper.writeValueAsString(body);
    }

    // ── Helpers de serialización ──────────────────────────────────────────────

    private void addUserText(ArrayNode contents, String text) {
        contents.addObject()
                .put("role", "user")
                .putArray("parts")
                .addObject()
                .put("text", text);
    }

    private void addModelText(ArrayNode contents, String text) {
        contents.addObject()
                .put("role", "model")
                .putArray("parts")
                .addObject()
                .put("text", text);
    }

    private void addModelFunctionCall(ArrayNode contents, String funcName,
                                      String arguments) throws Exception {
        ObjectNode funcCallObj = mapper.createObjectNode()
                .put("name", funcName);
        funcCallObj.set("args", mapper.readTree(arguments));

        ObjectNode part = mapper.createObjectNode();
        part.set("functionCall", funcCallObj);

        ObjectNode content = contents.addObject();
        content.put("role", "model");
        content.putArray("parts").add(part);
    }

    private void addFunctionResponse(ArrayNode contents, String funcName,
                                     String resultText) {
        ObjectNode responseObj = mapper.createObjectNode()
                .put("name", funcName)
                .put("content", resultText);

        ObjectNode part = mapper.createObjectNode();
        part.set("functionResponse", responseObj);

        ObjectNode content = contents.addObject();
        content.put("role", "user");
        content.putArray("parts").add(part);
    }

    /**
     * Extrae el nombre de la función del toolCallId (formato "gemini-{name}")
     * o usa el último nombre rastreado como fallback.
     */
    private static String extractFuncName(String toolCallId, String lastToolName) {
        if (toolCallId != null && toolCallId.startsWith("gemini-")) {
            return toolCallId.substring("gemini-".length());
        }
        return lastToolName != null ? lastToolName : "unknown_function";
    }

    // ── Streaming ──────────────────────────────────────────────────────────────

    /**
     * Gemini streaming uses the {@code streamGenerateContent} endpoint with
     * {@code alt=sse}. Each SSE event contains a complete JSON chunk in the same
     * format as a non-streaming response — the chunks are accumulated and a
     * single final {@link ModelResponse} is returned.
     *
     * <pre>
     * POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}
     *
     * data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}]}
     * data: {"candidates":[{"content":{"parts":[{"text":" world"}],"role":"model"}}]}
     * data: {"candidates":[{"content":{"parts":[{"text":"!"}],"role":"model"},"finishReason":"STOP"}]}
     * </pre>
     */
    @Override
    public ModelResponse generateStreaming(ModelRequest request, HttpModelConfig config,
                                           HttpClient httpClient,
                                           Consumer<String> onToken) throws Exception {
        String body = serializeRequest(request, config);

        // Streaming endpoint: streamGenerateContent instead of generateContent
        String streamEndpoint = endpoint(config)
                .replace(":generateContent", ":streamGenerateContent");
        String url = streamEndpoint + "?alt=sse&key="
                + URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(config.timeout())
                .POST(BodyPublishers.ofString(body))
                .build();

        var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String errorBody = response.body()
                    .filter(l -> !l.isBlank())
                    .reduce("", (a, b) -> a + b);
            throw new RuntimeException(
                    "Gemini returned HTTP %d: %s".formatted(response.statusCode(), errorBody));
        }

        StringBuilder fullContent = new StringBuilder();
        ModelResponse toolResponse = null;

        for (String line : (Iterable<String>) response.body()::iterator) {
            if (!line.startsWith("data: ")) continue;
            String data = line.substring(6).trim();

            try {
                JsonNode chunk = mapper.readTree(data);

                // Check for API error embedded in chunk
                if (chunk.has("error")) {
                    String msg = chunk.path("error").path("message").asText(data);
                    throw new RuntimeException("Gemini streaming error: " + msg);
                }

                JsonNode candidates = chunk.path("candidates");
                if (!candidates.isArray() || candidates.isEmpty()) continue;

                JsonNode parts = candidates.get(0).path("content").path("parts");
                for (JsonNode part : parts) {
                    // Tool call in a chunk — use parseResponse for full handling
                    if (!part.path("functionCall").isMissingNode()) {
                        toolResponse = parseResponse(data);
                        // Don't emit tokens for tool calls
                        break;
                    }

                    String text = part.path("text").asText(null);
                    if (text != null && !text.isEmpty()) {
                        fullContent.append(text);
                        onToken.accept(text);
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception ignored) {
                // Malformed chunk — skip
            }
        }

        return toolResponse != null ? toolResponse : new FinalAnswer(fullContent.toString());
    }

    @Override
    public boolean supportsStreaming() { return true; }
}
