package dev.rafex.etherbrain.http;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.common.JsonUtils;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.StepListener;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor HTTP que expone el runtime EtherBrain como API REST.
 *
 * <p>Construido sobre <strong>Jetty 12.1.10 Core API</strong> — reemplaza
 * {@code com.sun.net.httpserver} (JDK interno) para proveer:
 * <ul>
 *   <li>TLS/HTTPS nativo vía {@code HTTPS_PORT} + {@code HTTPS_KEYSTORE_*}</li>
 *   <li>HTTP/2 (añadir {@code jetty-alpn-java-server} al classpath)</li>
 *   <li>Virtual threads — todos los handlers corren en {@link VirtualThreadPool}</li>
 *   <li>Escritura SSE no bloqueante: cada evento de streaming se entrega
 *       de inmediato sin bloquear plataforma threads</li>
 * </ul>
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /health
 *   → 200  {"status":"ok"}
 *
 * POST /sessions/{id}/run
 *   Body:  {"message":"..."}
 *   → 200  {"sessionId":"...","answer":"...","requestId":"..."}
 *
 * POST /sessions/{id}/run/stream
 *   Body:  {"message":"..."}
 *   → 200  text/event-stream (SSE)
 *   Eventos: start | thinking | token | tool_call | tool_result | answer | error | done
 *
 * DELETE /sessions/{id}/cancel
 *   → 200  {"cancelled":true|false}
 *
 * POST /events
 *   Body:  {"session_id":"...","message":"...","callback_url":"(optional)"}
 *   → 202  {"queued":true,"position":N}
 * </pre>
 *
 * <h2>Configuración</h2>
 * <pre>
 * HTTP_PORT              — puerto HTTP (default: 8080)
 * HTTP_EVENT_QUEUE       — capacidad de la cola async (default: 100)
 * HTTPS_PORT             — puerto HTTPS; omitir para desactivar TLS
 * HTTPS_KEYSTORE_PATH    — ruta al keystore JKS/PKCS12
 * HTTPS_KEYSTORE_PASS    — contraseña del keystore
 * HTTP_AUTH_TOKEN        — Bearer token requerido (salvo /health)
 * HTTP_MAX_BODY_BYTES    — tamaño máximo del body (default: 65536)
 * HTTP_RATE_LIMIT_RPM    — peticiones por minuto por IP (0 = deshabilitado)
 * CALLBACK_ALLOW_PRIVATE — "true" para permitir callbacks a IPs privadas
 * </pre>
 */
public final class HttpAgentServer {

    private static final Pattern RUN_PATH    = Pattern.compile("^/sessions/([^/]+)/run$");
    private static final Pattern STREAM_PATH = Pattern.compile("^/sessions/([^/]+)/run/stream$");
    private static final Pattern CANCEL_PATH = Pattern.compile("^/sessions/([^/]+)/cancel$");

    /** SSE write timeout — how long to wait for a single event flush. */
    private static final long SSE_WRITE_TIMEOUT_SECONDS = 30;

    // ── Instance fields ───────────────────────────────────────────────────────

    private final AgentRuntime     runtime;
    private final int              port;
    private final MetricsCollector metrics;

    // Security
    private final String  authToken;
    private final int     maxBodyBytes;
    private final int     rateLimitRpm;
    private final boolean allowPrivateCallback;

    /** Rate-limit windows per IP — sliding 60-second window. */
    private final ConcurrentHashMap<String, java.util.ArrayDeque<Long>> rateWindows =
            new ConcurrentHashMap<>();

    /** Active cancellation tokens, keyed by sessionId. */
    private final ConcurrentHashMap<String, CancellationToken.Mutable> activeLoops =
            new ConcurrentHashMap<>();

    /** Async event queue. */
    private final BlockingQueue<AgentEvent> eventQueue;

    /** Shared HTTP client for outbound callbacks. */
    private final java.net.http.HttpClient callbackClient =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private Server jettyServer;

    // ── Constructors ──────────────────────────────────────────────────────────

    public HttpAgentServer(AgentRuntime runtime, int port, int threads, int eventQueueCapacity) {
        this(runtime, port, threads, eventQueueCapacity, MetricsCollector.noop());
    }

    public HttpAgentServer(AgentRuntime runtime, int port, int threads,
                           int eventQueueCapacity, MetricsCollector metrics) {
        this(runtime, port, threads, eventQueueCapacity, metrics,
                null, 65_536, 0, false);
    }

    /** Full constructor. */
    public HttpAgentServer(AgentRuntime runtime, int port, int threads,
                           int eventQueueCapacity, MetricsCollector metrics,
                           String authToken, int maxBodyBytes,
                           int rateLimitRpm, boolean allowPrivateCallback) {
        this.runtime              = runtime;
        this.port                 = port;
        this.metrics              = metrics != null ? metrics : MetricsCollector.noop();
        this.eventQueue           = new ArrayBlockingQueue<>(eventQueueCapacity);
        this.authToken            = (authToken != null && !authToken.isBlank()) ? authToken : null;
        this.maxBodyBytes         = maxBodyBytes > 0 ? maxBodyBytes : 65_536;
        this.rateLimitRpm         = Math.max(0, rateLimitRpm);
        this.allowPrivateCallback = allowPrivateCallback;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the Jetty server. Blocks until {@link #stop()} is called.
     *
     * <p>Uses {@link VirtualThreadPool} so every handler runs in a virtual thread —
     * blocking I/O (including SSE event writes) is safe without burning platform threads.
     */
    public void start() throws Exception {
        VirtualThreadPool pool = new VirtualThreadPool();
        pool.setName("etherbrain");

        jettyServer = new Server(pool);

        // HTTP connector
        ServerConnector httpConnector = new ServerConnector(jettyServer);
        httpConnector.setPort(port);
        jettyServer.addConnector(httpConnector);

        // Optional HTTPS connector
        String httpsPortStr = envOrProp("HTTPS_PORT", null);
        if (httpsPortStr != null && !httpsPortStr.isBlank()) {
            addHttpsConnector(jettyServer, Integer.parseInt(httpsPortStr));
        }

        jettyServer.setHandler(new EtherBrainHandler());

        startEventProcessor();
        jettyServer.start();

        EtherLog.info(HttpAgentServer.class, "Servidor Jetty iniciado en :{}", port);
        if (httpsPortStr != null) {
            EtherLog.info(HttpAgentServer.class, "HTTPS en :{}", httpsPortStr);
        }
        EtherLog.info(HttpAgentServer.class, "POST   /sessions/{{id}}/run");
        EtherLog.info(HttpAgentServer.class, "POST   /sessions/{{id}}/run/stream  (SSE)");
        EtherLog.info(HttpAgentServer.class, "DELETE /sessions/{{id}}/cancel");
        EtherLog.info(HttpAgentServer.class, "POST   /events");
        EtherLog.info(HttpAgentServer.class, "GET    /health");

        jettyServer.join();
    }

    public void stop() {
        if (jettyServer != null) {
            try {
                jettyServer.stop();
            } catch (Exception e) {
                EtherLog.warn(HttpAgentServer.class, "Error al detener Jetty: {}", e.getMessage());
            }
        }
    }

    // ── Optional HTTPS connector ──────────────────────────────────────────────

    /**
     * Adds an HTTPS {@link ServerConnector} to the server.
     *
     * <h3>Required env vars when {@code HTTPS_PORT} is set</h3>
     * <pre>
     * HTTPS_KEYSTORE_PATH  — path to JKS or PKCS12 keystore
     * HTTPS_KEYSTORE_PASS  — keystore password
     * </pre>
     *
     * <p>For self-signed certs during development:
     * <pre>
     * keytool -genkeypair -alias jetty -keyalg RSA -keysize 2048 \
     *   -validity 365 -keystore keystore.jks -storepass changeit
     * </pre>
     */
    private static void addHttpsConnector(Server server, int httpsPort) {
        try {
            String ksPath = envOrProp("HTTPS_KEYSTORE_PATH", null);
            String ksPass = envOrProp("HTTPS_KEYSTORE_PASS", "changeit");

            if (ksPath == null || ksPath.isBlank()) {
                EtherLog.warn(HttpAgentServer.class,
                        "HTTPS_PORT definido pero HTTPS_KEYSTORE_PATH no está configurado — " +
                        "conector HTTPS omitido.");
                return;
            }

            // Use reflection to avoid compile-time dependency on SslContextFactory
            // when TLS is not needed (optional feature)
            Class<?> sslClass = Class.forName("org.eclipse.jetty.util.ssl.SslContextFactory$Server");
            Object ssl = sslClass.getConstructor().newInstance();
            sslClass.getMethod("setKeyStorePath",  String.class).invoke(ssl, ksPath);
            sslClass.getMethod("setKeyStorePassword", String.class).invoke(ssl, ksPass);

            Class<?> sslConnClass = Class.forName("org.eclipse.jetty.server.SslConnectionFactory");
            Object sslConnFactory = sslConnClass
                    .getConstructor(sslClass.getSuperclass().getSuperclass(), String.class)
                    .newInstance(ssl, "http/1.1");

            Class<?> httpConnClass = Class.forName("org.eclipse.jetty.server.HttpConnectionFactory");
            Object httpConnFactory = httpConnClass.getConstructor().newInstance();

            Class<?> scClass = Class.forName("org.eclipse.jetty.server.ServerConnector");
            Object connector = scClass
                    .getConstructor(Server.class, java.util.concurrent.Executor.class,
                            org.eclipse.jetty.io.ByteBufferPool.class, Object[].class)
                    .newInstance(server, null, null,
                            new Object[]{sslConnFactory, httpConnFactory});

            scClass.getMethod("setPort", int.class).invoke(connector, httpsPort);
            server.getClass().getMethod("addConnector",
                    Class.forName("org.eclipse.jetty.server.Connector"))
                    .invoke(server, connector);

            EtherLog.info(HttpAgentServer.class, "HTTPS connector añadido en :{}", httpsPort);
        } catch (Exception e) {
            EtherLog.warn(HttpAgentServer.class,
                    "No se pudo configurar HTTPS: {} — servidor solo HTTP.", e.getMessage());
        }
    }

    // ── Jetty Handler ─────────────────────────────────────────────────────────

    private final class EtherBrainHandler extends Handler.Abstract {

        @Override
        public boolean handle(Request request, Response response, Callback callback)
                throws Exception {

            String path   = request.getHttpURI().getPath();
            String method = request.getMethod();

            // /health — always accessible, no auth
            if ("/health".equals(path) && "GET".equalsIgnoreCase(method)) {
                handleHealth(response, callback);
                return true;
            }

            // Auth check
            String authHeader = request.getHeaders().get(HttpHeader.AUTHORIZATION);
            if (!isAuthorized(authHeader)) {
                metrics.increment("http.unauthorized.total");
                sendJson(response, callback, null, 401,
                        json("error", "Unauthorized — provide Authorization: Bearer <token>"));
                return true;
            }

            // Rate limit (per IP)
            String clientIp = clientIp(request);
            if (isRateLimited(clientIp)) {
                metrics.increment("http.rate_limited.total", "endpoint=" + label(path));
                sendJson(response, callback, null, 429,
                        json("error", "Too many requests — try again later"));
                return true;
            }

            // POST /sessions/{id}/run/stream
            Matcher m = STREAM_PATH.matcher(path);
            if (m.matches() && "POST".equalsIgnoreCase(method)) {
                handleStream(request, response, callback, m.group(1));
                return true;
            }

            // DELETE /sessions/{id}/cancel
            m = CANCEL_PATH.matcher(path);
            if (m.matches() && "DELETE".equalsIgnoreCase(method)) {
                handleCancel(response, callback, m.group(1));
                return true;
            }

            // POST /sessions/{id}/run
            m = RUN_PATH.matcher(path);
            if (m.matches()) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(response, callback, null, 405,
                            json("error", "Method not allowed — use POST"));
                    return true;
                }
                handleRun(request, response, callback, m.group(1));
                return true;
            }

            // POST /events
            if ("/events".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleEvents(request, response, callback);
                return true;
            }

            sendJson(response, callback, null, 404, json("error", "Not found: " + path));
            return true;
        }
    }

    // ── /health ───────────────────────────────────────────────────────────────

    private static void handleHealth(Response response, Callback callback) {
        sendJson(response, callback, null, 200, "{\"status\":\"ok\"}");
    }

    // ── POST /sessions/{id}/run ───────────────────────────────────────────────

    private void handleRun(Request request, Response response, Callback callback,
                           String sessionId) throws Exception {
        String requestId = resolveRequestId(request);
        String body      = readBodyLimited(request, response, callback, requestId);
        if (body == null) return;

        String message = extractMessage(body);
        if (message == null) {
            sendJson(response, callback, requestId, 400,
                    json("error", "Body JSON debe incluir campo 'message'"));
            return;
        }

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

            String responseBody =
                    "{\"sessionId\":"  + jsonString(sessionId) +
                    ",\"answer\":"     + jsonString(answer)    +
                    ",\"requestId\":" + jsonString(requestId) + "}";
            sendJsonWithRequestId(response, callback, requestId, 200, responseBody);

        } catch (Exception e) {
            Duration latency = Duration.between(start, Instant.now());
            metrics.record("http.request.duration", latency,
                    "endpoint=run", "status=500", "requestId=" + requestId);
            metrics.increment("http.requests.total",
                    "endpoint=run", "status=500", "requestId=" + requestId);
            EtherLog.error(HttpAgentServer.class,
                    "Run failed — sessionId={} requestId={}: {}", sessionId, requestId, e.getMessage());
            sendJsonWithRequestId(response, callback, requestId, 500, json("error", e.getMessage()));

        } finally {
            activeLoops.remove(sessionId);
        }
    }

    // ── POST /sessions/{id}/run/stream ────────────────────────────────────────

    private void handleStream(Request request, Response response, Callback callback,
                              String sessionId) throws Exception {
        String requestId = resolveRequestId(request);
        String body      = readBodyLimited(request, response, callback, requestId);
        if (body == null) return;

        String message = extractMessage(body);
        if (message == null) {
            sendJson(response, callback, requestId, 400,
                    json("error", "Body JSON debe incluir campo 'message'"));
            return;
        }

        // Set SSE headers — must be done before first write
        response.setStatus(200);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.getHeaders().add(HttpHeader.CACHE_CONTROL, "no-cache");
        response.getHeaders().add("Connection",    "keep-alive");
        response.getHeaders().add("X-Request-ID",  requestId);

        CancellationToken.Mutable token = CancellationToken.create();
        activeLoops.put(sessionId, token);

        Instant start = Instant.now();
        try {
            trySseWrite(response,
                    "{\"type\":\"start\",\"sessionId\":" + jsonString(sessionId) +
                    ",\"requestId\":" + jsonString(requestId) + "}");

            StepListener listener = new StepListener() {
                @Override public void onStepStart(int step) {
                    trySseWrite(response,
                            "{\"type\":\"thinking\",\"step\":" + step + "}");
                }
                @Override public void onToken(int step, String tok) {
                    trySseWrite(response,
                            "{\"type\":\"token\",\"step\":" + step +
                            ",\"text\":" + jsonString(tok) + "}");
                }
                @Override public void onToolCall(int step, String tool, String args) {
                    trySseWrite(response,
                            "{\"type\":\"tool_call\",\"step\":" + step +
                            ",\"tool\":" + jsonString(tool) +
                            ",\"args\":" + jsonString(args) + "}");
                }
                @Override public void onToolResult(int step, String tool, boolean ok, String result) {
                    String truncated = result.length() > 500
                            ? result.substring(0, 500) + "…" : result;
                    trySseWrite(response,
                            "{\"type\":\"tool_result\",\"step\":" + step +
                            ",\"tool\":" + jsonString(tool) +
                            ",\"success\":" + ok +
                            ",\"content\":" + jsonString(truncated) + "}");
                }
                @Override public void onFinalAnswer(String answer) { /* sent below */ }
                @Override public void onError(String error) {
                    trySseWrite(response,
                            "{\"type\":\"error\",\"content\":" + jsonString(error) + "}");
                }
            };

            String answer = runtime.run(sessionId, message, token, listener, requestId);

            Duration latency = Duration.between(start, Instant.now());
            metrics.record("http.request.duration", latency,
                    "endpoint=stream", "status=200", "requestId=" + requestId);
            metrics.increment("http.requests.total",
                    "endpoint=stream", "status=200", "requestId=" + requestId);

            trySseWrite(response, "{\"type\":\"answer\",\"content\":" + jsonString(answer) + "}");
            writeFinalSseEvent(response, callback, "{\"type\":\"done\"}");

        } catch (Exception e) {
            Duration latency = Duration.between(start, Instant.now());
            metrics.record("http.request.duration", latency,
                    "endpoint=stream", "status=500", "requestId=" + requestId);
            metrics.increment("http.requests.total",
                    "endpoint=stream", "status=500", "requestId=" + requestId);
            EtherLog.error(HttpAgentServer.class,
                    "Stream failed — sessionId={} requestId={}: {}",
                    sessionId, requestId, e.getMessage());
            trySseWrite(response,
                    "{\"type\":\"error\",\"content\":" + jsonString(e.getMessage()) + "}");
            writeFinalSseEvent(response, callback, "{\"type\":\"done\"}");

        } finally {
            activeLoops.remove(sessionId);
        }
    }

    // ── DELETE /sessions/{id}/cancel ─────────────────────────────────────────

    private void handleCancel(Response response, Callback callback, String sessionId) {
        CancellationToken.Mutable token = activeLoops.get(sessionId);
        if (token != null) {
            token.cancel();
            sendJson(response, callback, null, 200,
                    "{\"cancelled\":true,\"sessionId\":" + jsonString(sessionId) + "}");
        } else {
            sendJson(response, callback, null, 200,
                    "{\"cancelled\":false,\"reason\":\"No active loop for session\"}");
        }
    }

    // ── POST /events ──────────────────────────────────────────────────────────

    private void handleEvents(Request request, Response response, Callback callback)
            throws Exception {
        String body = readBodyLimited(request, response, callback, null);
        if (body == null) return;

        String sessionId = extractField(body, "session_id");
        String message   = extractMessage(body);
        String cbUrl     = extractField(body, "callback_url");

        if (sessionId == null || message == null) {
            sendJson(response, callback, null, 400,
                    json("error", "Body must include 'session_id' and 'message'"));
            return;
        }

        // SSRF protection
        if (cbUrl != null && !cbUrl.isBlank() && !isSafeCallbackUrl(cbUrl)) {
            EtherLog.warn(HttpAgentServer.class,
                    "Rejected unsafe callback_url='{}' for sessionId={}", cbUrl, sessionId);
            sendJson(response, callback, null, 400,
                    json("error", "callback_url points to a private/loopback address"));
            return;
        }

        AgentEvent event = new AgentEvent(sessionId, message, cbUrl);
        if (!eventQueue.offer(event)) {
            metrics.increment("event.queue.rejected", "sessionId=" + sessionId);
            EtherLog.warn(HttpAgentServer.class,
                    "Event queue full — rejected sessionId={}", sessionId);
            sendJson(response, callback, null, 503,
                    json("error", "Event queue full — try again later"));
            return;
        }

        metrics.increment("event.queued", "sessionId=" + sessionId);
        metrics.gauge("event.queue.size", eventQueue.size());
        sendJson(response, callback, null, 202,
                "{\"queued\":true,\"position\":" + eventQueue.size() + "}");
    }

    // ── Async event processor ─────────────────────────────────────────────────

    private void startEventProcessor() {
        Thread.ofVirtual().name("event-processor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AgentEvent event = eventQueue.poll(5, TimeUnit.SECONDS);
                    if (event == null) continue;
                    Thread.ofVirtual().name("event-" + event.sessionId()).start(
                            () -> processEvent(event));
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

    // ── Security helpers (package-private for unit tests) ─────────────────────

    boolean isAuthorized(String authHeader) {
        if (authToken == null) return true;
        return ("Bearer " + authToken).equals(authHeader);
    }

    boolean isRateLimited(String ip) {
        if (rateLimitRpm <= 0) return false;
        long now         = System.currentTimeMillis();
        long windowStart = now - 60_000L;
        java.util.ArrayDeque<Long> window =
                rateWindows.computeIfAbsent(ip, k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.pollFirst();
            }
            if (window.size() >= rateLimitRpm) return true;
            window.addLast(now);
            return false;
        }
    }

    boolean isSafeCallbackUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            if (allowPrivateCallback) return true;
            java.net.InetAddress addr = java.net.InetAddress.getByName(uri.getHost());
            return !addr.isLoopbackAddress()
                    && !addr.isSiteLocalAddress()
                    && !addr.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Request helpers ───────────────────────────────────────────────────────

    private static String resolveRequestId(Request request) {
        String incoming = request.getHeaders().get("X-Request-ID");
        if (incoming != null && !incoming.isBlank()) {
            String safe = incoming.replaceAll("[^a-zA-Z0-9\\-]", "");
            if (!safe.isBlank()) return safe.substring(0, Math.min(safe.length(), 64));
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String readBodyLimited(Request request, Response response, Callback callback,
                                   String requestId) {
        String clHeader = request.getHeaders().get(HttpHeader.CONTENT_LENGTH);
        if (clHeader != null) {
            try {
                long cl = Long.parseLong(clHeader.trim());
                if (cl > maxBodyBytes) {
                    sendJson(response, callback, requestId, 413,
                            json("error", "Request body too large — max " + maxBodyBytes + " bytes"));
                    return null;
                }
            } catch (NumberFormatException ignored) {}
        }
        try (InputStream in = Content.Source.asInputStream(request)) {
            byte[] bytes = in.readNBytes(maxBodyBytes + 1);
            if (bytes.length > maxBodyBytes) {
                sendJson(response, callback, requestId, 413,
                        json("error", "Request body too large — max " + maxBodyBytes + " bytes"));
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            sendJson(response, callback, requestId, 400,
                    json("error", "Cannot read request body"));
            return null;
        }
    }

    private static String clientIp(Request request) {
        SocketAddress remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return remote.toString();
    }

    private static String label(String path) {
        if (path.contains("/run/stream")) return "stream";
        if (path.contains("/run"))        return "run";
        if (path.contains("/cancel"))     return "cancel";
        if (path.startsWith("/events"))   return "events";
        return "other";
    }

    // ── Response helpers ──────────────────────────────────────────────────────

    private static void sendJson(Response response, Callback callback,
                                 String requestId, int status, String body) {
        response.setStatus(status);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8");
        if (requestId != null) response.getHeaders().add("X-Request-ID", requestId);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().add(HttpHeader.CONTENT_LENGTH, String.valueOf(bytes.length));
        // response.write(last, buffer, callback) — Jetty 12 Core API
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    private static void sendJsonWithRequestId(Response response, Callback callback,
                                              String requestId, int status, String body) {
        sendJson(response, callback, requestId, status, body);
    }

    /** Writes one SSE event frame. Blocks the virtual thread until flushed. */
    private static void trySseWrite(Response response, String data) {
        try {
            byte[] bytes = ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            var future = new java.util.concurrent.CompletableFuture<Void>();
            response.write(false, ByteBuffer.wrap(bytes), Callback.from(
                    () -> future.complete(null),
                    future::completeExceptionally));
            future.get(SSE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Client disconnected or timeout — drop silently
        }
    }

    /**
     * Writes the last SSE event and completes the Jetty {@link Callback},
     * which signals end-of-response to the framework.
     */
    private static void writeFinalSseEvent(Response response, Callback callback, String data) {
        try {
            byte[] bytes = ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            response.write(true, ByteBuffer.wrap(bytes), Callback.from(
                    callback::succeeded,
                    t -> { callback.failed(t); }));
        } catch (Exception e) {
            callback.failed(e);
        }
    }

    // ── JSON / field extraction helpers ──────────────────────────────────────

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

    /** Builds and starts the server from environment variables. */
    public static HttpAgentServer fromEnv() {
        ApplicationBootstrap bootstrap = new ApplicationBootstrap();
        AgentRuntime     runtime  = bootstrap.bootstrap();
        MetricsCollector metrics  = ApplicationBootstrap.buildMetricsCollector();

        int    port           = Integer.parseInt(envOrProp("HTTP_PORT",            "8080"));
        int    eventQueue     = Integer.parseInt(envOrProp("HTTP_EVENT_QUEUE",     "100"));
        String authToken      = envOrProp("HTTP_AUTH_TOKEN",      null);
        int    maxBodyBytes   = Integer.parseInt(envOrProp("HTTP_MAX_BODY_BYTES",  "65536"));
        int    rateLimitRpm   = Integer.parseInt(envOrProp("HTTP_RATE_LIMIT_RPM", "0"));
        boolean allowPrivate  = "true".equalsIgnoreCase(
                envOrProp("CALLBACK_ALLOW_PRIVATE", "false"));

        return new HttpAgentServer(runtime, port, 0, eventQueue, metrics,
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
