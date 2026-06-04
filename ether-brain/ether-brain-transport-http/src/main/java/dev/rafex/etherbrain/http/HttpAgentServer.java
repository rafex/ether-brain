package dev.rafex.etherbrain.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.common.JsonUtils;
import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.StepListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

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
 * HTTP_THREADS           — hilos del servidor (default: 4)
 * HTTP_EVENT_QUEUE       — capacidad máxima de la cola de eventos (default: 100)
 *
 * <h2>Security settings</h2>
 * <pre>
 * HTTP_AUTH_TOKEN        — Bearer token obligatorio en todos los endpoints salvo /health;
 *                          omitir para deshabilitar autenticación (entornos de desarrollo).
 * HTTP_MAX_BODY_BYTES    — Tamaño máximo del body en bytes (default: 65536 = 64 KB).
 * HTTP_RATE_LIMIT_RPM    — Peticiones por minuto por IP (0 = deshabilitado, default: 0).
 * CALLBACK_ALLOW_PRIVATE — "true" para permitir callbacks a IPs privadas/loopback
 *                          (default: false; protege contra SSRF).
 * </pre>
 */
public final class HttpAgentServer {

    private static final java.util.regex.Pattern RUN_PATH    =
            java.util.regex.Pattern.compile("^/sessions/([^/]+)/run$");
    private static final java.util.regex.Pattern STREAM_PATH =
            java.util.regex.Pattern.compile("^/sessions/([^/]+)/run/stream$");
    private static final java.util.regex.Pattern CANCEL_PATH =
            java.util.regex.Pattern.compile("^/sessions/([^/]+)/cancel$");

    private final AgentRuntime     runtime;
    private final int              port;
    private final int              threads;
    private final MetricsCollector metrics;

    // ── Security fields ───────────────────────────────────────────────────────
    private final String  authToken;           // null = no auth
    private final int     maxBodyBytes;        // max request body size
    private final int     rateLimitRpm;        // 0 = disabled
    private final boolean allowPrivateCallback;

    /** Rate-limit windows per IP — sliding 60-second window. */
    private final ConcurrentHashMap<String, java.util.ArrayDeque<Long>> rateWindows =
            new ConcurrentHashMap<>();

    // ── Runtime fields ────────────────────────────────────────────────────────

    /** Active cancellation tokens, keyed by sessionId. */
    private final ConcurrentHashMap<String, CancellationToken.Mutable> activeLoops =
            new ConcurrentHashMap<>();

    /** Async event queue for reactive processing. */
    private final BlockingQueue<AgentEvent> eventQueue;

    /** Shared HTTP client for outbound callbacks — one instance, shared connection pool. */
    private final java.net.http.HttpClient callbackClient =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private HttpServer server;

    public HttpAgentServer(AgentRuntime runtime, int port, int threads, int eventQueueCapacity) {
        this(runtime, port, threads, eventQueueCapacity, MetricsCollector.noop());
    }

    public HttpAgentServer(AgentRuntime runtime, int port, int threads,
                           int eventQueueCapacity, MetricsCollector metrics) {
        this(runtime, port, threads, eventQueueCapacity, metrics,
                null, 65_536, 0, false);
    }

    /** Full constructor — all security settings explicit. */
    public HttpAgentServer(AgentRuntime runtime, int port, int threads,
                           int eventQueueCapacity, MetricsCollector metrics,
                           String authToken, int maxBodyBytes,
                           int rateLimitRpm, boolean allowPrivateCallback) {
        this.runtime              = runtime;
        this.port                 = port;
        this.threads              = threads;
        this.metrics              = metrics != null ? metrics : MetricsCollector.noop();
        this.eventQueue           = new ArrayBlockingQueue<>(eventQueueCapacity);
        this.authToken            = (authToken != null && !authToken.isBlank()) ? authToken : null;
        this.maxBodyBytes         = maxBodyBytes > 0 ? maxBodyBytes : 65_536;
        this.rateLimitRpm         = Math.max(0, rateLimitRpm);
        this.allowPrivateCallback = allowPrivateCallback;
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

        EtherLog.info(HttpAgentServer.class, "Servidor HTTP iniciado en :{}", port);
        EtherLog.info(HttpAgentServer.class, "POST   /sessions/{{id}}/run");
        EtherLog.info(HttpAgentServer.class, "POST   /sessions/{{id}}/run/stream  (SSE)");
        EtherLog.info(HttpAgentServer.class, "DELETE /sessions/{{id}}/cancel");
        EtherLog.info(HttpAgentServer.class, "POST   /events                    (async)");
        EtherLog.info(HttpAgentServer.class, "GET    /health");
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    // ── Security helpers ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the request carries a valid {@code Authorization: Bearer} token,
     * or if no token is configured (auth disabled).
     */
    private boolean isAuthorized(HttpExchange ex) {
        if (authToken == null) return true;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return ("Bearer " + authToken).equals(header);
    }

    /**
     * Returns {@code true} if the given IP address has exceeded {@link #rateLimitRpm}
     * requests within the last 60 seconds. Thread-safe sliding-window implementation.
     * Always returns {@code false} when {@link #rateLimitRpm} is 0 (disabled).
     */
    private boolean isRateLimited(String ip) {
        if (rateLimitRpm <= 0) return false;
        long now         = System.currentTimeMillis();
        long windowStart = now - 60_000L;

        java.util.ArrayDeque<Long> window = rateWindows.computeIfAbsent(
                ip, k -> new java.util.ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.pollFirst();
            }
            if (window.size() >= rateLimitRpm) return true;
            window.addLast(now);
            return false;
        }
    }

    /**
     * Returns {@code true} if the URL is safe to use as a callback destination.
     *
     * <p>Blocks loopback, RFC-1918 private, and link-local addresses to prevent
     * Server-Side Request Forgery (SSRF) attacks.  Set
     * {@code CALLBACK_ALLOW_PRIVATE=true} to bypass in trusted internal environments.
     */
    private boolean isSafeCallbackUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (allowPrivateCallback) return true;
            java.net.InetAddress addr = java.net.InetAddress.getByName(uri.getHost());
            return !addr.isLoopbackAddress()
                    && !addr.isSiteLocalAddress()   // RFC-1918: 10.x, 172.16.x, 192.168.x
                    && !addr.isLinkLocalAddress();  // 169.254.x.x (AWS metadata, etc.)
        } catch (Exception e) {
            return false;  // unparseable URL = not safe
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"status\":\"ok\"}");
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        // Auth check
        if (!isAuthorized(ex)) {
            respond(ex, 401, json("error", "Unauthorized — provide Authorization: Bearer <token>"));
            return;
        }
        // Rate limit check (per client IP)
        String clientIp = ex.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(clientIp)) {
            metrics.increment("http.rate_limited.total", "endpoint=sessions");
            respond(ex, 429, json("error", "Too many requests — try again later"));
            return;
        }

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
        // Auth + rate limit
        if (!isAuthorized(ex)) {
            respond(ex, 401, json("error", "Unauthorized — provide Authorization: Bearer <token>"));
            return;
        }
        String clientIp = ex.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(clientIp)) {
            metrics.increment("http.rate_limited.total", "endpoint=events");
            respond(ex, 429, json("error", "Too many requests — try again later"));
            return;
        }

        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, json("error", "Method not allowed — use POST"));
            return;
        }
        String body = readBodyLimited(ex);
        if (body == null) return;  // error already sent

        String sessionId = extractField(body, "session_id");
        String message   = extractMessage(body);
        String callback  = extractField(body, "callback_url");

        if (sessionId == null || message == null) {
            respond(ex, 400, json("error",
                    "Body must include 'session_id' and 'message'"));
            return;
        }

        // SSRF protection — validate callback URL before accepting the event
        if (callback != null && !callback.isBlank() && !isSafeCallbackUrl(callback)) {
            EtherLog.warn(HttpAgentServer.class,
                    "Rejected unsafe callback_url='{}' from ip={}", callback, clientIp);
            respond(ex, 400, json("error",
                    "callback_url points to a private/loopback address or uses an unsupported scheme"));
            return;
        }

        AgentEvent event = new AgentEvent(sessionId, message, callback);
        boolean queued = eventQueue.offer(event);
        if (!queued) {
            metrics.increment("event.queue.rejected", "sessionId=" + sessionId);
            EtherLog.warn(HttpAgentServer.class,
                    "Event queue full — rejected sessionId={}", sessionId);
            respond(ex, 503, json("error", "Event queue full — try again later"));
            return;
        }

        metrics.increment("event.queued", "sessionId=" + sessionId);
        metrics.gauge("event.queue.size", eventQueue.size());
        respond(ex, 202,
                "{\"queued\":true,\"position\":" + eventQueue.size() + "}");
    }

    // ── Sync run ──────────────────────────────────────────────────────────────

    private void handleRun(HttpExchange ex, String sessionId) throws IOException {
        String requestId = resolveRequestId(ex);
        String message   = readMessage(ex);
        if (message == null) return;

        CancellationToken.Mutable token = CancellationToken.create();
        activeLoops.put(sessionId, token);

        Instant start = Instant.now();
        try {
            String answer = runtime.run(sessionId, message, token, null, requestId);
            Duration latency = Duration.between(start, Instant.now());
            metrics.record("http.request.duration", latency,
                    "endpoint=run", "status=200", "requestId=" + requestId);
            metrics.increment("http.requests.total",
                    "endpoint=run", "status=200", "requestId=" + requestId);
            respondWithRequestId(ex, requestId, 200,
                    "{\"sessionId\":" + jsonString(sessionId) +
                    ",\"answer\":"    + jsonString(answer)    +
                    ",\"requestId\":" + jsonString(requestId) + "}");
        } catch (Exception e) {
            Duration latency = Duration.between(start, Instant.now());
            metrics.record("http.request.duration", latency,
                    "endpoint=run", "status=500", "requestId=" + requestId);
            metrics.increment("http.requests.total",
                    "endpoint=run", "status=500", "requestId=" + requestId);
            EtherLog.error(HttpAgentServer.class,
                    "Run failed — sessionId={} requestId={}: {}", sessionId, requestId, e.getMessage());
            respondWithRequestId(ex, requestId, 500, json("error", e.getMessage()));
        } finally {
            activeLoops.remove(sessionId);
        }
    }

    // ── SSE streaming run ─────────────────────────────────────────────────────

    private void handleStream(HttpExchange ex, String sessionId) throws IOException {
        String requestId = resolveRequestId(ex);
        String message   = readMessage(ex);
        if (message == null) return;

        ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("X-Request-ID",  requestId);
        ex.sendResponseHeaders(200, 0);

        CancellationToken.Mutable token = CancellationToken.create();
        activeLoops.put(sessionId, token);

        Instant start = Instant.now();
        try (OutputStream out = ex.getResponseBody()) {
            // Start event — include requestId so client can correlate
            writeEvent(out, "{\"type\":\"start\",\"sessionId\":" + jsonString(sessionId)
                    + ",\"requestId\":" + jsonString(requestId) + "}");

            // Step listener: emits SSE events for each loop step
            StepListener listener = new StepListener() {
                @Override public void onStepStart(int step) {
                    trySse(out, "{\"type\":\"thinking\",\"step\":" + step + "}");
                }
                @Override public void onToken(int step, String tok) {
                    trySse(out, "{\"type\":\"token\",\"step\":" + step
                            + ",\"text\":" + jsonString(tok) + "}");
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
                    String answer = runtime.run(sessionId, msg, token, listener, requestId);
                    Duration latency = Duration.between(start, Instant.now());
                    metrics.record("http.request.duration", latency,
                            "endpoint=stream", "status=200", "requestId=" + requestId);
                    metrics.increment("http.requests.total",
                            "endpoint=stream", "status=200", "requestId=" + requestId);
                    try {
                        writeEvent(out, "{\"type\":\"answer\",\"content\":" + jsonString(answer) + "}");
                        writeEvent(out, "{\"type\":\"done\"}");
                    } catch (IOException ignored) {}
                } catch (Exception e) {
                    Duration latency = Duration.between(start, Instant.now());
                    metrics.record("http.request.duration", latency,
                            "endpoint=stream", "status=500", "requestId=" + requestId);
                    metrics.increment("http.requests.total",
                            "endpoint=stream", "status=500", "requestId=" + requestId);
                    EtherLog.error(HttpAgentServer.class,
                            "Stream failed — sessionId={} requestId={}: {}",
                            sessionId, requestId, e.getMessage());
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
        Instant start = Instant.now();
        try {
            String answer = runtime.run(event.sessionId(), event.message());
            metrics.record("event.processing.duration", Duration.between(start, Instant.now()),
                    "sessionId=" + event.sessionId(), "status=ok");
            metrics.increment("event.processed.total",
                    "sessionId=" + event.sessionId(), "status=ok");
            if (event.callbackUrl() != null && !event.callbackUrl().isBlank()) {
                sendCallback(event.callbackUrl(), event.sessionId(), answer, null);
            }
        } catch (Exception e) {
            metrics.record("event.processing.duration", Duration.between(start, Instant.now()),
                    "sessionId=" + event.sessionId(), "status=error");
            metrics.increment("event.processed.total",
                    "sessionId=" + event.sessionId(), "status=error");
            EtherLog.error(HttpAgentServer.class,
                    "Event processing failed — sessionId={}: {}",
                    event.sessionId(), e.getMessage());
            if (event.callbackUrl() != null && !event.callbackUrl().isBlank()) {
                sendCallback(event.callbackUrl(), event.sessionId(), null, e.getMessage());
            }
        }
    }

    private void sendCallback(String callbackUrl, String sessionId, String answer, String error) {
        try {
            String body = answer != null
                    ? "{\"sessionId\":" + jsonString(sessionId) + ",\"answer\":" + jsonString(answer) + "}"
                    : "{\"sessionId\":" + jsonString(sessionId) + ",\"error\":" + jsonString(error) + "}";

            // Uses the shared HttpClient (one connection pool for the server lifetime)
            callbackClient.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(callbackUrl))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());

            metrics.increment("callback.sent.total", "status=ok");
        } catch (Exception e) {
            metrics.increment("callback.sent.total", "status=error");
            EtherLog.warn(HttpAgentServer.class,
                    "Callback failed — url={}: {}", callbackUrl, e.getMessage());
        }
    }

    // ── Request helpers ───────────────────────────────────────────────────────

    /**
     * Reads the request body up to {@link #maxBodyBytes}.
     * Sends a 413 response and returns {@code null} if the limit is exceeded.
     */
    private String readBodyLimited(HttpExchange ex) throws IOException {
        // Reject early if Content-Length header says it's too large
        String clHeader = ex.getRequestHeaders().getFirst("Content-Length");
        if (clHeader != null) {
            try {
                long cl = Long.parseLong(clHeader.trim());
                if (cl > maxBodyBytes) {
                    respond(ex, 413, json("error",
                            "Request body too large — max " + maxBodyBytes + " bytes"));
                    return null;
                }
            } catch (NumberFormatException ignored) {}
        }

        byte[] bytes;
        try {
            // Read at most maxBodyBytes + 1 so we detect exactly-at-limit bodies
            bytes = ex.getRequestBody().readNBytes(maxBodyBytes + 1);
        } catch (IOException e) {
            respond(ex, 400, json("error", "Cannot read request body"));
            return null;
        }
        if (bytes.length > maxBodyBytes) {
            respond(ex, 413, json("error",
                    "Request body too large — max " + maxBodyBytes + " bytes"));
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String readMessage(HttpExchange ex) throws IOException {
        String body = readBodyLimited(ex);
        if (body == null) return null;
        String message = extractMessage(body);
        if (message == null) {
            respond(ex, 400, json("error",
                    "Body JSON debe incluir campo 'message'. " +
                    "Ejemplo: {\"message\":\"¿Quién eres?\"}"));
            return null;
        }
        return message;
    }

    // ── Request ID helpers ────────────────────────────────────────────────────

    /**
     * Returns the value of the incoming {@code X-Request-ID} header if present,
     * otherwise generates a new 8-character correlation ID.
     *
     * <p>By accepting a client-supplied ID, distributed systems can propagate
     * correlation identifiers end-to-end (e.g. an API gateway sets the header
     * and the agent's logs carry the same ID).
     */
    private static String resolveRequestId(HttpExchange ex) {
        String incoming = ex.getRequestHeaders().getFirst("X-Request-ID");
        if (incoming != null && !incoming.isBlank()) {
            // Sanitize — keep only alphanumeric and hyphens, max 64 chars
            String safe = incoming.replaceAll("[^a-zA-Z0-9\\-]", "");
            if (!safe.isBlank()) return safe.substring(0, Math.min(safe.length(), 64));
        }
        // Generate a short ID: first 8 chars of a UUID (collision-safe for logging)
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ── Response helpers ──────────────────────────────────────────────────────

    /** Respond with JSON body and set {@code X-Request-ID} header. */
    private static void respondWithRequestId(HttpExchange ex, String requestId,
                                             int status, String body) throws IOException {
        ex.getResponseHeaders().set("X-Request-ID", requestId);
        respond(ex, status, body);
    }

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
        return JsonUtils.extractField(json, "message");
    }

    static String extractField(String json, String field) {
        return JsonUtils.extractField(json, field);
    }

    private static String json(String key, String value) {
        return "{" + jsonString(key) + ":" + jsonString(value) + "}";
    }

    static String jsonString(String s) {
        return JsonUtils.toJsonString(s);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Construye el servidor desde variables de entorno estándar. */
    public static HttpAgentServer fromEnv() {
        ApplicationBootstrap bootstrap = new ApplicationBootstrap();
        AgentRuntime     runtime  = bootstrap.bootstrap();
        MetricsCollector metrics  = ApplicationBootstrap.buildMetricsCollector();

        int    port               = Integer.parseInt(envOrProp("HTTP_PORT",        "8080"));
        int    threads            = Integer.parseInt(envOrProp("HTTP_THREADS",     "4"));
        int    eventQueue         = Integer.parseInt(envOrProp("HTTP_EVENT_QUEUE", "100"));
        String authToken          = envOrProp("HTTP_AUTH_TOKEN",   null);
        int    maxBodyBytes       = Integer.parseInt(envOrProp("HTTP_MAX_BODY_BYTES",   "65536"));
        int    rateLimitRpm       = Integer.parseInt(envOrProp("HTTP_RATE_LIMIT_RPM",   "0"));
        boolean allowPrivate      = "true".equalsIgnoreCase(envOrProp("CALLBACK_ALLOW_PRIVATE", "false"));

        return new HttpAgentServer(runtime, port, threads, eventQueue, metrics,
                authToken, maxBodyBytes, rateLimitRpm, allowPrivate);
    }

    private static String envOrProp(String name, String def) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        if (v != null && !v.isBlank()) return v;
        return def;
    }

    // ── Internal DTOs ─────────────────────────────────────────────────────────

    private record AgentEvent(String sessionId, String message, String callbackUrl) {}
}
