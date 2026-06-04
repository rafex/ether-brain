package dev.rafex.etherbrain.tools.local;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.tools.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Carga tools en el {@link InMemoryToolRegistry} desde cuatro fuentes:
 *
 * <ol>
 *   <li><b>Archivo JSON</b> ({@code AGENT_TOOLS_FILE} o {@code tools.json}) — soporta
 *       cuatro tipos de tool:</li>
 *     <ul>
 *       <li>{@code "subprocess"} (default) — ejecuta un proceso externo</li>
 *       <li>{@code "http"}  — delega a un endpoint HTTP REST</li>
 *       <li>{@code "mcp"}   — conecta a un servidor MCP (stdio) y registra todas sus tools</li>
 *     </ul>
 *   <li><b>SPI / ServiceLoader</b> — descubre implementaciones de {@link Tool}
 *       en el classpath ({@code META-INF/services/...Tool})</li>
 * </ol>
 *
 * <h2>Formato completo del archivo JSON</h2>
 * <pre>{@code
 * [
 *   // Subprocess — cualquier CLI como tool
 *   {
 *     "type":    "subprocess",          // opcional, default
 *     "name":    "ocr_document",
 *     "description": "...",
 *     "command": ["ether-ocr", "ocr", "${file_path}", "${__output__}"],
 *     "output":  "file",               // "stdout" | "file"
 *     "input_schema": { "type": "object", "properties": {...}, "required": [...] }
 *   },
 *
 *   // HTTP Proxy — cualquier servicio HTTP
 *   {
 *     "type":        "http",
 *     "name":        "search_web",
 *     "description": "...",
 *     "endpoint":    "http://localhost:8090/search",
 *     "method":      "POST",           // "GET" | "POST" (default: POST)
 *     "headers":     {"X-API-Key": "${MY_API_KEY}"},
 *     "timeout_seconds": 15,
 *     "input_schema": { "type": "object", "properties": {...} }
 *   },
 *
 *   // MCP — servidor MCP stdio (registra TODAS sus tools automáticamente)
 *   {
 *     "type":        "mcp",
 *     "server_name": "ether-ocr-mcp",   // nombre para logs
 *     "command":     ["python3", "-m", "ether_ocr_mcp"],
 *     "env":         {"PYTHONPATH": "/ruta/a/ether-ocr/python/src"}
 *   }
 * ]
 * }</pre>
 *
 * <h2>SPI — JAR externo</h2>
 * Cualquier JAR en el classpath con el archivo:
 * <pre>
 * META-INF/services/dev.rafex.etherbrain.ports.tools.Tool
 * </pre>
 * que liste implementaciones de {@link Tool} será descubierto y registrado
 * automáticamente al llamar a {@link #loadFromClasspath(InMemoryToolRegistry)}.
 */
public final class ExternalToolLoader {

    /** Soporta comentarios // y /* y trailing commas en el tools.json */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private ExternalToolLoader() {}

    // ── Fuente 1: archivo JSON ────────────────────────────────────────────────

    /**
     * Carga tools desde el archivo JSON.
     * No lanza excepción si el archivo no existe.
     *
     * @return número de tools registradas (para MCP: suma de tools por servidor)
     */
    public static int load(InMemoryToolRegistry registry) {
        Path file = resolveFile();
        if (file == null) return 0;

        try {
            JsonNode array = MAPPER.readTree(Files.readString(file));
            if (!array.isArray()) {
                System.err.println("[ExternalToolLoader] " + file + " debe ser array JSON.");
                return 0;
            }

            int total = 0;
            for (JsonNode def : array) {
                String type = def.path("type").asText("subprocess");
                try {
                    total += switch (type) {
                        case "subprocess", ""  -> loadSubprocess(def, registry);
                        case "http"            -> loadHttp(def, registry);
                        case "mcp"             -> loadMcp(def, registry);
                        default -> {
                            System.err.println("[ExternalToolLoader] Tipo desconocido: " + type);
                            yield 0;
                        }
                    };
                } catch (Exception e) {
                    System.err.println("[ExternalToolLoader] Error cargando tool (" + type + "): "
                            + e.getMessage());
                }
            }

            if (total > 0) {
                System.out.println("[EtherBrain] " + total
                        + " tool(s) externas cargadas desde " + file);
            }
            return total;

        } catch (Exception e) {
            System.err.println("[ExternalToolLoader] No se pudo leer " + file
                    + ": " + e.getMessage());
            return 0;
        }
    }

    // ── Fuente 2: SPI / ServiceLoader ─────────────────────────────────────────

    /**
     * Descubre implementaciones de {@link Tool} en el classpath mediante
     * {@link ServiceLoader} y las registra.
     *
     * @return número de tools registradas
     */
    public static int loadFromClasspath(InMemoryToolRegistry registry) {
        int count = 0;
        for (Tool tool : ServiceLoader.load(Tool.class)) {
            registry.register(tool);
            System.out.println("[EtherBrain] tool SPI: " + tool.name()
                    + " (" + tool.getClass().getSimpleName() + ")");
            count++;
        }
        if (count > 0) {
            System.out.println("[EtherBrain] " + count + " tool(s) SPI en classpath.");
        }
        return count;
    }

    // ── Parsers por tipo ──────────────────────────────────────────────────────

    private static int loadSubprocess(JsonNode def, InMemoryToolRegistry registry) {
        String name        = require(def, "name");
        String description = require(def, "description");
        String schema      = requireSchema(def, name);

        JsonNode cmdNode = def.path("command");
        if (!cmdNode.isArray() || cmdNode.isEmpty()) {
            throw new IllegalArgumentException("tool '" + name + "': 'command' es obligatorio");
        }
        List<String> cmd = new ArrayList<>();
        for (JsonNode t : cmdNode) cmd.add(t.asText());

        ExternalTool.OutputMode mode = "file".equalsIgnoreCase(def.path("output").asText("stdout"))
                ? ExternalTool.OutputMode.FILE
                : ExternalTool.OutputMode.STDOUT;

        registry.register(new ExternalTool(name, description, schema, cmd, mode));
        System.out.println("[EtherBrain] tool subprocess: " + name
                + " (cmd: " + cmd.get(0) + " ...)");
        return 1;
    }

    private static int loadHttp(JsonNode def, InMemoryToolRegistry registry) {
        String name     = require(def, "name");
        String desc     = require(def, "description");
        String schema   = requireSchema(def, name);
        String endpoint = require(def, "endpoint");
        String method   = def.path("method").asText("POST");
        int    timeout  = def.path("timeout_seconds").asInt(30);

        Map<String, String> headers = new HashMap<>();
        def.path("headers").fields().forEachRemaining(
                e -> headers.put(e.getKey(), e.getValue().asText()));

        registry.register(new HttpProxyTool(name, desc, schema,
                endpoint, method, headers, timeout));
        System.out.println("[EtherBrain] tool http: " + name + " → " + endpoint);
        return 1;
    }

    private static int loadMcp(JsonNode def, InMemoryToolRegistry registry) throws Exception {
        String serverName = def.path("server_name").asText(
                def.path("command").path(0).asText("mcp-server"));

        JsonNode cmdNode = def.path("command");
        if (!cmdNode.isArray() || cmdNode.isEmpty()) {
            throw new IllegalArgumentException("MCP '" + serverName + "': 'command' es obligatorio");
        }
        List<String> cmd = new ArrayList<>();
        for (JsonNode t : cmdNode) cmd.add(t.asText());

        // Variables de entorno del servidor MCP
        Map<String, String> env = new HashMap<>();
        def.path("env").fields().forEachRemaining(
                e -> env.put(e.getKey(), HttpProxyTool.resolveEnv(e.getValue().asText())));

        McpClient client = new McpClient(serverName, cmd, env);
        List<McpClient.McpToolInfo> tools = client.listTools();

        if (tools.isEmpty()) {
            System.out.println("[EtherBrain] MCP '" + serverName + "': sin tools disponibles.");
            client.close();
            return 0;
        }

        for (McpClient.McpToolInfo info : tools) {
            registry.register(new McpToolAdapter(client, info));
            System.out.println("[EtherBrain] tool mcp: " + info.name()
                    + " ← " + serverName);
        }
        return tools.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String require(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.asText().isBlank()) {
            throw new IllegalArgumentException("Campo requerido ausente: " + field);
        }
        return v.asText();
    }

    private static String requireSchema(JsonNode node, String toolName) {
        JsonNode s = node.path("input_schema");
        if (s.isMissingNode()) {
            throw new IllegalArgumentException("tool '" + toolName + "': 'input_schema' obligatorio");
        }
        return s.toString();
    }

    private static Path resolveFile() {
        String explicit = System.getenv("AGENT_TOOLS_FILE");
        if (explicit == null) explicit = System.getProperty("AGENT_TOOLS_FILE");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.exists(p)) return p;
            System.err.println("[ExternalToolLoader] AGENT_TOOLS_FILE no existe: " + p);
            return null;
        }
        Path cwd = Path.of("tools.json");
        return Files.exists(cwd) ? cwd : null;
    }
}
