package dev.rafex.etherbrain.tools.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Tool genérica que delega a un endpoint HTTP externo.
 *
 * <p>Permite exponer cualquier microservicio o API REST como tool del agente
 * sin escribir código Java. La definición viene de {@link ExternalToolLoader}.
 *
 * <h2>Comportamiento</h2>
 * <ol>
 *   <li>Serializa los argumentos del modelo como JSON body (método POST) o
 *       query params (método GET).</li>
 *   <li>Envía la request al endpoint configurado.</li>
 *   <li>Devuelve el body de la respuesta como resultado de la tool.</li>
 * </ol>
 *
 * <h2>Variables de entorno en headers</h2>
 * Los valores de los headers se resuelven desde el entorno:
 * <pre>
 * "headers": {"Authorization": "Bearer ${MY_API_TOKEN}"}
 * </pre>
 * {@code ${MY_API_TOKEN}} se sustituye por el valor de la variable de entorno.
 *
 * <h2>Ejemplo en tools.json</h2>
 * <pre>{@code
 * {
 *   "type":        "http",
 *   "name":        "search_web",
 *   "description": "Searches the web and returns top results.",
 *   "endpoint":    "http://localhost:8090/search",
 *   "method":      "POST",
 *   "headers":     {"X-API-Key": "${SEARCH_API_KEY}"},
 *   "timeout_seconds": 15,
 *   "input_schema": {
 *     "type": "object",
 *     "properties": {
 *       "query": {"type": "string", "description": "Search query"}
 *     },
 *     "required": ["query"]
 *   }
 * }
 * }</pre>
 */
public final class HttpProxyTool implements Tool {

    private final String      name;
    private final String      description;
    private final String      inputSchema;
    private final String      endpoint;
    private final String      method;
    private final Map<String, String> headers;
    private final int         timeoutSeconds;
    private final ObjectMapper mapper  = new ObjectMapper();
    private final HttpClient   client  = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpProxyTool(String name, String description, String inputSchema,
                          String endpoint, String method,
                          Map<String, String> headers, int timeoutSeconds) {
        this.name           = name;
        this.description    = description;
        this.inputSchema    = inputSchema;
        this.endpoint       = endpoint;
        this.method         = method == null ? "POST" : method.toUpperCase();
        this.headers        = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    @Override public String name()        { return name; }
    @Override public String description() { return description; }
    @Override public String inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        String body = arguments == null ? "{}" : arguments;

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");

        // Resolver y añadir headers (sustituyendo ${ENV_VAR})
        for (var entry : headers.entrySet()) {
            req.header(entry.getKey(), resolveEnv(entry.getValue()));
        }

        if ("GET".equals(method)) {
            // GET: pasar argumentos como query params
            JsonNode args    = mapper.readTree(body);
            StringBuilder qs = new StringBuilder(endpoint.contains("?") ? "&" : "?");
            args.fields().forEachRemaining(e ->
                    qs.append(e.getKey()).append("=").append(e.getValue().asText()).append("&"));
            req.uri(URI.create(endpoint + qs.toString().replaceAll("&$", "")));
            req.GET();
        } else {
            req.method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            String result = resp.body();
            return new ToolResult(name, true, result.isBlank() ? "(empty response)" : result);
        }

        return new ToolResult(name, false,
                "HTTP " + resp.statusCode() + " from " + endpoint + ": " + resp.body());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Sustituye {@code ${VAR}} por el valor de la variable de entorno. */
    static String resolveEnv(String value) {
        if (value == null || !value.contains("${")) return value;
        var sb  = new StringBuilder(value);
        var m   = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(value);
        int adj = 0;
        while (m.find()) {
            String env = System.getenv(m.group(1));
            if (env == null) env = System.getProperty(m.group(1), "");
            sb.replace(m.start() + adj, m.end() + adj, env);
            adj += env.length() - (m.end() - m.start());
        }
        return sb.toString();
    }
}
