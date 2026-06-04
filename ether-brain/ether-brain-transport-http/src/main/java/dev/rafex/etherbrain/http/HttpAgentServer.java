package dev.rafex.etherbrain.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.StepListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor HTTP que expone el runtime EtherBrain como API REST con soporte para
 * streaming SSE, cancelación de loops y procesamiento de eventos asíncronos.
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /health
 *   → 200  {"status":"ok"}
 *
 * POST /sessions/{id}/run
 *   Body:  {"message":"..."}
 *   → 200  {"sessionId":"...","answer":"..."}
 *
 * POST /sessions/{id}/run/stream
 *   Body:  {"message":"..."}
 *   → 200  text/event-stream (SSE)
 *   Eventos: data: {"type":"start"}
 *            data: {"type":"answer","content":"..."}
 *            data: {"type":"error","content":"..."}
 *            data: {"type":"done"}
 *
 * DELETE /sessions/{id}/cancel
 *   → 200  {"cancelled":true}
 *   Cancela el loop activo para esa sesión (si existe).
 *
 * POST /events
 *   Body:  {"session_id":"...","message":"...","callback_url":"(optional)"}
 *   → 202  {"queued":true,"position":N}
 *   Encola un mensaje para procesamiento asíncrono.
 *   Si callback_url está presente, el resultado se envía vía POST.
 * </pre>
 *
 * <h2>Configuración</h2>
 * <pre>
 * HTTP_PORT         — puerto de escucha (default 8080)
 * HTTP_THREADS      — hilos del servidor (default 4)
 * HTTP_EVENT_QUEUE  — capacidad máxima de la cola de eventos (default 100)
 * </pre>
 */
public final class HttpAgentServer {

    private static final Pattern RUN_PATH      = Pattern.compile("^/sessions/([^/]+)/run$");
    private static final Pattern STREAM_PATH   = Pattern.compile("^/sessions/([^/]+)/run/stream$");
    private static final Pattern CANCEL_PATH   = Pattern.compile("^/sessions/([^/]+)/cancel$");
    private static final Pattern EVENTS_PATH   = Pattern.compile("^/events$");

    private final AgentRuntime runtime;
    private final int port;
    private final int threads;

    /** Active cancellation tokens, keyed by sessionId. */
    private final ConcurrentHashMap<String, CancellationToken.Mutable> activeLoops =
            new ConcurrentHashMap<>();

    /** Async event queue for reactive processing. */
    private final BlockingQueue<AgentEvent> eventQueue;

    private HttpServer server;

    public HttpAgentServer(AgentRuntime runtime, int port, int threads, int eventQueueCapacity) {
        this.runtime    = runtime;
        this.port       = port;
        this.threads    = threads;
        this.eventQueue = new ArrayBlockingQueue<>(eventQueueCapacity);
    }

    /** Inicia el servidor y bloquea hasta que se llame a {@link #stop()}. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health",   this::handleHealth);
        server.createContext("/sessions", this::handleSessions);
        server.createContext("/events",   this::handleEvents);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        // Start async event processor
        startEventProcessor();

        System.out.printf("[EtherBrain HTTP] Escuchando en :%d%n", port);
        System.out.println("[EtherBrain HTTP] POST   /sessions/{id}/run");
        System.out.println("[EtherBrain HTTP] POST   /sessions/{id}/run/stream  (SSE)");
        System.out.println("[EtherBrain HTTP] DELETE /sessions/{id}/cancel");
        System.out.println("[EtherBrain HTTP] POST   /events                    (async)");
        System.out.println("[EtherBrain HTTP] GET    /health");
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

        // POST /sessions/{id}/run/stream — SSE
        Matcher streamM = STREAM_PATH.matcher(path);
        if (streamM.matches() && "POST".equalsIgnoreCase(method)) {
            handleStream(ex, streamM.group(1));
            return;
        }

        // DELETE /sessions/{id}/cancel
        Matcher cancelM = CANCEL_PATH.matcher(path);
        if (cancelM.matches() && "DELETE".equalsIgnoreCase(method)) {
            handleCancel(ex, cancelM.group(1));
            return;
        }

        // POST /sessions/{id}/run — sync
        Matcher runM = RUN_PATH.matcher(path);
        if (runM.matches()) {
            if (!"POST".equalsIgnoreCase(method)) {
                respond(ex, 405, json("error", "Method not allowed — use POST"));
                return;
            }
            handleRun(ex, runM.group(1));
            return;
        }

        respond(ex, 404, json("error", "Not found: " + path));
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, json("error", "Method not allowed — use POST"));
            return;
        }
        String body;
        try {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            respond(ex, 400, json("error", "Cannot read request body"));
            return;
        }

        String sessionId = extractField(body, "session_id");
        String message   = extractMessage(body);
        String callback  = extractField(body, "callback_url");

        if (sessionId == null || message == null) {
            respond(ex, 400, json("error",
                    "Body must include 'session_id' and 'message'"));
            return;
        }

        AgentEvent event = new AgentEvent(sessionId, message, callback);
        boolean queued = eventQueue.offer(event);
        if (!queued) {
            respond(ex, 503, json("error", "Event queue full — try again later"));
            return;
        }

        respond(ex, 202,
                "{\"queued\":true,\"position\":" + eventQueue.size() + "}");
    }

    // ── Sync run ──────────────────────────────────────────────────────────────

    private void handleRun(HttpExchange ex, String sessionId) throws IOException {
        String message = readMessage(ex);
        if (message == null) return;

        CancellationToken.Mutable token = CancellationToken.create();
        activeLoops.put(sessionId, token);

        try {
            String answer = runtime.run(sessionId, message, token);
            respond(ex, 200,
                    "{\"sessionId\":" + jsonString(sessionId) +
                    ",\"answer\":"    + jsonString(answer) + "}");
        } catch (Exception e) {
            respond(ex, 500, json("error", e.getMessage()));
        } finally {
            activeLoops.remove(sessionId);
        }
    }

    // ── SSE streaming run ─────────────────────────────────────────────────────

    private void handleStream(HttpExchange ex, String sessionId) throws IOException {
        String message = readMessage(ex);
        if (message == null) return;

        ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.sendResponseHeaders(200, 0);

        CancellationToken.Mutable token = CancellationToken.create();
        activeLoops.put(sessionId, token);

        try (OutputStream out = ex.getResponseBody()) {
            // Start event
            writeEvent(out, "{\"type\":\"start\",\"sessionId\":" + jsonString(sessionId) + "}");

            // Step listener: emits SSE events for each loop step
            StepListener listener = new StepListener() {
                @Override public void onStepStart(int step) {
                    trySse(out, "{\"type\":\"thinking\",\"step\":" + step + "}");
                }
                @Override public void onToken(int step, String token) {
                    // Real-time token streaming — emitted for each text chunk from the LLM
                    trySse(out, "{\"type\":\"token\",\"step\":" + step
                            + ",\"text\":" + jsonString(token) + "}");
                }
                @Override public void onToolCall(int step, String tool, String args) {
                    trySse(out, "{\"type\":\"tool_call\",\"step\":" + step
                            + ",\"tool\":" + jsonString(tool)
                            + ",\"args\":" + jsonString(args) + "}");
                }
                @Override public void onToolResult(int step, String tool, boolean ok, String result) {
                    trySse(out, "{\"type\":\"tool_result\",\"step\":" + step
                            + ",\"tool\":" + jsonString(tool)
                            + ",\"success\":" + ok
                            + ",\"content\":" + jsonString(
                                    result.length() > 500 ? result.substring(0, 500) + "…" : result)
                            + "}");
                }
                @Override public void onFinalAnswer(String answer) { /* sent after join */ }
                @Override public void onError(String error) {
                    trySse(out, "{\"type\":\"error\",\"content\":" + jsonString(error) + "}");
                }
            };

            // Run in virtual thread with step listener attached
            final String msg = message;
            Thread.ofVirtual().start(() -> {
                try {
                    String answer = runtime.run(sessionId, msg, token, listener);
                    try {
                        writeEvent(out, "{\"type\":\"answer\",\"content\":" + jsonString(answer) + "}");
                        writeEvent(out, "{\"type\":\"done\"}");
                    } catch (IOException ignored) {}
                } catch (Exception e) {
                    try {
                        writeEvent(out, "{\"type\":\"error\",\"content\":" +
                                jsonString(e.getMessage()) + "}");
                        writeEvent(out, "{\"type\":\"done\"}");
                    } catch (IOException ignored) {}
                } finally {
                    activeLoops.remove(sessionId);
                }
            }).join();  // wait for virtual thread

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client disconnected
        } finally {
            activeLoops.remove(sessionId);
        }
    }

    private static void writeEvent(OutputStream out, String data) throws IOException {
        String sseFrame = "data: " + data + "\n\n";
        out.write(sseFrame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Write an SSE event, silently dropping it if the client disconnected. */
    private static void trySse(OutputStream out, String data) {
        try { writeEvent(out, data); } catch (IOException ignored) {}
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    private void handleCancel(HttpExchange ex, String sessionId) throws IOException {
        CancellationToken.Mutable token = activeLoops.get(sessionId);
        if (token != null) {
            token.cancel();
            respond(ex, 200, "{\"cancelled\":true,\"sessionId\":" + jsonString(sessionId) + "}");
        } else {
            respond(ex, 200, "{\"cancelled\":false,\"reason\":\"No active loop for session\"}");
        }
    }

    // ── Async event processor ─────────────────────────────────────────────────

    private void startEventProcessor() {
        Thread.ofVirtual().name("event-processor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AgentEvent event = eventQueue.poll(5, TimeUnit.SECONDS);
                    if (event == null) continue;

                    Thread.ofVirtual().name("event-" + event.sessionId()).start(() ->
                            processEvent(event));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void processEvent(AgentEvent event) {
        try {
            String answer = runtime.run(event.sessionId(), event.message());
            if (event.callbackUrl() != null && !event.callbackUrl().isBlank()) {
                sendCallback(event.callbackUrl(), event.sessionId(), answer, null);
            }
        } catch (Exception e) {
            if (event.callbackUrl() != null && !event.callbackUrl().isBlank()) {
                sendCallback(event.callbackUrl(), event.sessionId(), null, e.getMessage());
            }
            System.err.println("[EtherBrain HTTP] Event processing failed for session "
                    + event.sessionId() + ": " + e.getMessage());
        }
    }

    private void sendCallback(String callbackUrl, String sessionId, String answer, String error) {
        try {
            String body = answer != null
                    ? "{\"sessionId\":" + jsonString(sessionId) + ",\"answer\":" + jsonString(answer) + "}"
                    : "{\"sessionId\":" + jsonString(sessionId) + ",\"error\":" + jsonString(error) + "}";

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(callbackUrl))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());

        } catch (Exception e) {
            System.err.println("[EtherBrain HTTP] Callback failed to " + callbackUrl
                    + ": " + e.getMessage());
        }
    }

    // ── Request helpers ───────────────────────────────────────────────────────

    private String readMessage(HttpExchange ex) throws IOException {
        String body;
        try {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            respond(ex, 400, json("error", "Cannot read request body"));
            return null;
        }
        String message = extractMessage(body);
        if (message == null) {
            respond(ex, 400, json("error",
                    "Body JSON debe incluir campo 'message'. " +
                    "Ejemplo: {\"message\":\"¿Quién eres?\"}"));
            return null;
        }
        return message;
    }

    // ── Response helpers ──────────────────────────────────────────────────────

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
        return extractField(json, "message");
    }

    static String extractField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1)
                           .replace("\\\"", "\"")
                           .replace("\\n",  "\n")
                           .replace("\\t",  "\t")
                           .replace("\\\\", "\\")
                : null;
    }

    private static String json(String key, String value) {
        return "{" + jsonString(key) + ":" + jsonString(value) + "}";
    }

    static String jsonString(String s) {
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
        int port       = Integer.parseInt(envOrProp("HTTP_PORT",       "8080"));
        int threads    = Integer.parseInt(envOrProp("HTTP_THREADS",    "4"));
        int eventQueue = Integer.parseInt(envOrProp("HTTP_EVENT_QUEUE","100"));
        return new HttpAgentServer(runtime, port, threads, eventQueue);
    }

    private static String envOrProp(String name, String def) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : def;
    }

    // ── Internal DTOs ─────────────────────────────────────────────────────────

    private record AgentEvent(String sessionId, String message, String callbackUrl) {}
}
