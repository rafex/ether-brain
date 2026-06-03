package dev.rafex.etherbrain.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor HTTP minimalista que expone el runtime EtherBrain como API REST.
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /health
 *   → 200  {"status":"ok"}
 *
 * POST /sessions/{id}/run
 *   Body:  {"message":"..."}
 *   → 200  {"sessionId":"...","answer":"..."}
 *   → 400  {"error":"..."}   (message faltante o body inválido)
 *   → 405  {"error":"Method not allowed"}
 *   → 500  {"error":"..."}   (fallo interno del agente)
 * </pre>
 *
 * <h2>Configuración</h2>
 * <pre>
 * HTTP_PORT    — puerto de escucha (default 8080)
 * HTTP_THREADS — hilos del executor del servidor (default 4)
 * </pre>
 * Más todas las variables estándar de EtherBrain (LLM_URL, LLM_TOKEN, etc.).
 */
public final class HttpAgentServer {

    private static final Pattern RUN_PATH =
            Pattern.compile("^/sessions/([^/]+)/run$");

    private final AgentRuntime runtime;
    private final int port;
    private final int threads;
    private HttpServer server;

    public HttpAgentServer(AgentRuntime runtime, int port, int threads) {
        this.runtime = runtime;
        this.port    = port;
        this.threads = threads;
    }

    /** Inicia el servidor y bloquea hasta que se llame a {@link #stop()}. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/sessions", this::handleSessions);
        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.start();
        System.out.printf("[EtherBrain HTTP] Escuchando en :%d%n", port);
        System.out.println("[EtherBrain HTTP] POST /sessions/{id}/run");
        System.out.println("[EtherBrain HTTP] GET  /health");
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"status\":\"ok\"}");
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        Matcher m = RUN_PATH.matcher(path);
        if (!m.matches()) {
            respond(ex, 404, json("error", "Not found: " + path));
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            respond(ex, 405, json("error", "Method not allowed — use POST"));
            return;
        }

        String sessionId = m.group(1);
        String body;
        try {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            respond(ex, 400, json("error", "Cannot read request body"));
            return;
        }

        String message = extractMessage(body);
        if (message == null) {
            respond(ex, 400, json("error",
                    "Body JSON debe incluir campo 'message'. " +
                    "Ejemplo: {\"message\":\"¿Quién eres?\"}"));
            return;
        }

        try {
            String answer = runtime.run(sessionId, message);
            respond(ex, 200,
                    "{\"sessionId\":" + jsonString(sessionId) +
                    ",\"answer\":"    + jsonString(answer) + "}");
        } catch (Exception e) {
            respond(ex, 500, json("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Extrae {@code "message"} de un JSON simple sin librería externa. */
    static String extractMessage(String json) {
        if (json == null || json.isBlank()) return null;
        // Busca "message":"..." o "message": "..." con escape básico
        Matcher m = Pattern.compile(
                "\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1)
                           .replace("\\\"", "\"")
                           .replace("\\n", "\n")
                           .replace("\\t", "\t")
                           .replace("\\\\", "\\")
                : null;
    }

    private static String json(String key, String value) {
        return "{" + jsonString(key) + ":" + jsonString(value) + "}";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Construye el servidor desde variables de entorno estándar. */
    public static HttpAgentServer fromEnv() {
        AgentRuntime runtime = new ApplicationBootstrap().bootstrap();
        int port    = Integer.parseInt(System.getProperty("HTTP_PORT",
                System.getenv().getOrDefault("HTTP_PORT", "8080")));
        int threads = Integer.parseInt(System.getProperty("HTTP_THREADS",
                System.getenv().getOrDefault("HTTP_THREADS", "4")));
        return new HttpAgentServer(runtime, port, threads);
    }
}
