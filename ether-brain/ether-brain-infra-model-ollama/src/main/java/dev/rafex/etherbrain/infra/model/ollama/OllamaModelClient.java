package dev.rafex.etherbrain.infra.model.ollama;

import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model client for Ollama (local LLM runtime).
 * <p>
 * Connects to the Ollama API endpoint ({@code /api/chat}).
 * Supports any model pulled locally (llama3.2, mistral, gemma, etc.).
 * Uses {@link java.net.http.HttpClient} (built-in, no external dependencies).
 * <p>
 * Configuration keys:
 * <ul>
 *   <li>{@code OLLAMA_URL} (optional, default: {@code http://localhost:11434})</li>
 *   <li>{@code OLLAMA_MODEL} (optional, default: {@code llama3.2})</li>
 * </ul>
 */
public final class OllamaModelClient implements ModelClient {

    private static final Pattern TOOL_PATTERN = Pattern.compile(
            "TOOL:(.+?)\\s*\\nARGS:(.+)", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;

    public OllamaModelClient(Map<String, String> config) {
        this.baseUrl = config.getOrDefault("OLLAMA_URL", "http://localhost:11434");
        this.model = config.getOrDefault("OLLAMA_MODEL", "llama3.2");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ModelResponse generate(ModelRequest request) throws IOException, InterruptedException {
        Map<String, Object> body = buildRequestBody(request);
        String json = toJson(body);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned status " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    private Map<String, Object> buildRequestBody(ModelRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message msg : request.messages()) {
            messages.add(Map.of(
                    "role", msg.role().name().toLowerCase(),
                    "content", msg.content()
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.2);
        body.put("options", options);
        return body;
    }

    private ModelResponse parseResponse(String json) {
        String content = extractJsonString(json, "content");
        if (content == null || content.isBlank()) {
            return new FinalAnswer("");
        }

        Matcher matcher = TOOL_PATTERN.matcher(content);
        if (matcher.find()) {
            return new ToolRequest(matcher.group(1).trim(), matcher.group(2).trim());
        }

        if (content.startsWith("FINAL:")) {
            return new FinalAnswer(content.substring("FINAL:".length()).trim());
        }

        return new FinalAnswer(content.trim());
    }

    static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == startQuote + 1 || json.charAt(i - 1) != '\\')) {
                return sb.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> next;
                });
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            appendJsonValue(sb, entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> sb.append("\"").append(s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")).append("\"");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append("{");
                boolean first = true;
                for (var entry : m.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(entry.getKey()).append("\":");
                    appendJsonValue(sb, entry.getValue());
                    first = false;
                }
                sb.append("}");
            }
            case List<?> list -> {
                sb.append("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(",");
                    appendJsonValue(sb, item);
                    first = false;
                }
                sb.append("]");
            }
            default -> sb.append("\"").append(value).append("\"");
        }
    }
}
