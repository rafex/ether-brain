package dev.rafex.etherbrain.infra.model.openai;

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
 * Model client for OpenAI-compatible APIs.
 * <p>
 * Connects to the OpenAI Chat Completions endpoint ({@code /v1/chat/completions}).
 * Uses {@link java.net.http.HttpClient} (built-in, no external dependencies).
 * Parses the response to produce {@link FinalAnswer} or {@link ToolRequest}.
 * <p>
 * Configuration keys:
 * <ul>
 *   <li>{@code OPENAI_API_KEY} (required) — API key for authentication</li>
 *   <li>{@code OPENAI_BASE_URL} (optional, default: {@code https://api.openai.com})</li>
 *   <li>{@code OPENAI_MODEL} (optional, default: {@code gpt-4o-mini})</li>
 * </ul>
 */
public final class OpenAiModelClient implements ModelClient {

    private static final Pattern TOOL_PATTERN = Pattern.compile(
            "TOOL:(.+?)\\s*\\nARGS:(.+)", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public OpenAiModelClient(Map<String, String> config) {
        String key = config.get("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is required for OpenAI provider");
        }
        this.apiKey = key;
        this.baseUrl = config.getOrDefault("OPENAI_BASE_URL", "https://api.openai.com");
        this.model = config.getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ModelResponse generate(ModelRequest request) throws IOException, InterruptedException {
        Map<String, Object> body = buildRequestBody(request);
        String json = toJson(body);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI returned status " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    private Map<String, Object> buildRequestBody(ModelRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message msg : request.messages()) {
            messages.add(Map.of(
                    "role", toOpenAiRole(msg.role()),
                    "content", msg.content()
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        return body;
    }

    private static String toOpenAiRole(Message.Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    private ModelResponse parseResponse(String json) {
        String content = extractJsonString(json, "\"content\"");
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

    /**
     * Minimal JSON string extraction without external JSON library.
     * Extracts the first occurrence of {@code "key":"value"} or {@code "key": "value"}.
     */
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

    /**
     * Minimal JSON builder without external library.
     */
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
