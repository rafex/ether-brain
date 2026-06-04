package dev.rafex.etherbrain.tools.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente MCP (Model Context Protocol) sobre transporte stdio.
 *
 * <p>Lanza el servidor MCP como subproceso, se comunica mediante JSON-RPC 2.0
 * sobre stdin/stdout del proceso y expone una API síncrona para descubrir
 * e invocar tools.
 *
 * <h2>Protocolo</h2>
 * <pre>
 * Cliente → Servidor  (stdin del proceso)
 *   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
 *   {"jsonrpc":"2.0","method":"notifications/initialized"}
 *   {"jsonrpc":"2.0","id":2,"method":"tools/list"}
 *   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"...","arguments":{...}}}
 *
 * Servidor → Cliente  (stdout del proceso)
 *   {"jsonrpc":"2.0","id":1,"result":{...}}
 *   {"jsonrpc":"2.0","id":2,"result":{"tools":[...]}}
 *   {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"..."}]}}
 * </pre>
 *
 * <p>Un hilo de fondo lee continuamente stdout del servidor y resuelve los
 * {@link CompletableFuture} correspondientes a cada request por su {@code id}.
 */
public final class McpClient implements AutoCloseable {

    // ── Registro global para shutdown limpio ──────────────────────────────────
    static final List<McpClient> ACTIVE = new CopyOnWriteArrayList<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                ACTIVE.forEach(c -> { try { c.close(); } catch (Exception ignored) {} })
        ));
    }

    private static final ObjectMapper MAPPER        = new ObjectMapper();
    private static final long         TIMEOUT_SECS  = 30;

    private final String        serverName;
    private final Process       process;
    private final BufferedWriter stdin;
    private final AtomicInteger idGen = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();

    /**
     * Lanza el servidor MCP y realiza el handshake de inicialización.
     *
     * @param serverName nombre legible para logs
     * @param command    tokens del comando para iniciar el servidor
     * @param extraEnv   variables de entorno adicionales
     */
    public McpClient(String serverName, List<String> command,
                     Map<String, String> extraEnv) throws Exception {
        this.serverName = serverName;

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);   // stderr queda separado (visible en consola)
        pb.environment().put("MCP_TRANSPORT", "stdio");
        if (extraEnv != null) pb.environment().putAll(extraEnv);

        this.process = pb.start();
        this.stdin   = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream()));

        // Hilo de fondo: lee stdout del servidor y resuelve futures
        Thread reader = Thread.ofVirtual().name("mcp-reader-" + serverName).start(() -> {
            try (var out = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode msg = MAPPER.readTree(line);
                        int     id  = msg.path("id").asInt(-1);
                        if (id >= 0) {
                            CompletableFuture<JsonNode> f = pending.remove(id);
                            if (f != null) f.complete(msg);
                        }
                        // notifications (sin id) se ignoran
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
        reader.setDaemon(true);

        ACTIVE.add(this);
        initialize();
        System.out.println("[McpClient] Conectado a: " + serverName);
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /** Lista las tools disponibles en el servidor. */
    public List<McpToolInfo> listTools() throws Exception {
        JsonNode resp   = request("tools/list", null);
        JsonNode tools  = resp.path("result").path("tools");
        List<McpToolInfo> result = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                result.add(new McpToolInfo(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        t.path("inputSchema").toString()
                ));
            }
        }
        return result;
    }

    /** Invoca una tool en el servidor MCP. */
    public String callTool(String toolName, String argumentsJson) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", MAPPER.readTree(
                argumentsJson == null ? "{}" : argumentsJson));

        JsonNode resp    = request("tools/call", params);
        JsonNode content = resp.path("result").path("content");

        // MCP devuelve content como array de {type, text}
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String text = item.path("text").asText(item.path("data").asText(""));
                if (!text.isBlank()) sb.append(text);
            }
        } else {
            // Fallback: serializar el resultado completo
            sb.append(resp.path("result").toString());
        }
        return sb.isEmpty() ? "(empty)" : sb.toString().strip();
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
        try { stdin.close();   } catch (Exception ignored) {}
        try { process.destroy(); } catch (Exception ignored) {}
    }

    public String serverName() { return serverName; }

    // ── Internos ──────────────────────────────────────────────────────────────

    private void initialize() throws Exception {
        // 1. initialize
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "EtherBrain");
        clientInfo.put("version", "0.1.0");

        ObjectNode caps = MAPPER.createObjectNode();
        caps.set("tools", MAPPER.createObjectNode());

        ObjectNode initParams = MAPPER.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.set("capabilities", caps);
        initParams.set("clientInfo", clientInfo);

        request("initialize", initParams);

        // 2. notifications/initialized (notificación, sin id)
        ObjectNode notif = MAPPER.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        writeLine(notif.toString());
    }

    private JsonNode request(String method, JsonNode params) throws Exception {
        int id = idGen.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", params);

        writeLine(msg.toString());

        JsonNode resp = future.get(TIMEOUT_SECS, TimeUnit.SECONDS);

        // Propagar errores del servidor
        if (resp.has("error")) {
            throw new RuntimeException("MCP error from " + serverName + ": " +
                    resp.path("error").path("message").asText(resp.toString()));
        }
        return resp;
    }

    private synchronized void writeLine(String json) throws Exception {
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record McpToolInfo(String name, String description, String inputSchema) {}
}
