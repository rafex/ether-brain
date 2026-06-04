package dev.rafex.etherbrain.tools.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga tools externas desde un archivo JSON y las registra en el {@link InMemoryToolRegistry}.
 *
 * <h2>Activación</h2>
 * Define la variable de entorno {@code AGENT_TOOLS_FILE} apuntando al archivo JSON:
 * <pre>
 * AGENT_TOOLS_FILE=/ruta/a/tools.json
 * </pre>
 * Si no está definida, busca {@code tools.json} en el directorio de trabajo.
 *
 * <h2>Formato del archivo</h2>
 * <pre>{@code
 * [
 *   {
 *     "name":        "ocr_document",
 *     "description": "Extracts text from PDF or images using ether-ocr.",
 *     "input_schema": {
 *       "type": "object",
 *       "properties": {
 *         "file_path": {
 *           "type": "string",
 *           "description": "Absolute path to the file"
 *         }
 *       },
 *       "required": ["file_path"]
 *     },
 *     "command": ["ether-ocr", "ocr", "${file_path}", "${__output__}"],
 *     "output":  "file"
 *   },
 *   {
 *     "name":        "run_script",
 *     "description": "Runs a custom analysis script and returns stdout.",
 *     "input_schema": {
 *       "type": "object",
 *       "properties": {
 *         "input": {"type": "string", "description": "Data to process"}
 *       },
 *       "required": ["input"]
 *     },
 *     "command": ["python3", "/scripts/analyze.py", "${input}"],
 *     "output":  "stdout"
 *   }
 * ]
 * }</pre>
 *
 * <h2>Campos</h2>
 * <ul>
 *   <li>{@code name}         — identificador único de la tool (snake_case)</li>
 *   <li>{@code description}  — descripción que el modelo usa para decidir cuándo invocarla</li>
 *   <li>{@code input_schema} — JSON Schema de los argumentos que acepta</li>
 *   <li>{@code command}      — lista de tokens del comando; soporta {@code ${argName}} y {@code ${__output__}}</li>
 *   <li>{@code output}       — {@code "stdout"} o {@code "file"} (default: {@code "stdout"})</li>
 * </ul>
 *
 * <h2>Variables de entorno por tool</h2>
 * Puedes pasar variables de entorno específicas a cada tool con el prefijo
 * {@code TOOL_{NAME}_ENV_{VAR}}:
 * <pre>
 * TOOL_OCR_DOCUMENT_ENV_PYTHONPATH=/ruta/a/ether-ocr/python/src
 * </pre>
 */
public final class ExternalToolLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExternalToolLoader() {}

    /**
     * Carga tools externas y las registra.
     * No lanza excepción si el archivo no existe — simplemente no registra nada.
     *
     * @return número de tools cargadas
     */
    public static int load(InMemoryToolRegistry registry) {
        Path file = resolveFile();
        if (file == null) return 0;

        try {
            String json = Files.readString(file);
            JsonNode array = MAPPER.readTree(json);

            if (!array.isArray()) {
                System.err.println("[ExternalToolLoader] " + file +
                        " debe ser un array JSON. Ignorado.");
                return 0;
            }

            int loaded = 0;
            for (JsonNode def : array) {
                try {
                    ExternalTool tool = parse(def);
                    registry.register(tool);
                    System.out.println("[EtherBrain] tool externa: " + tool.name() +
                            " (cmd: " + def.path("command").path(0).asText() + " ...)");
                    loaded++;
                } catch (Exception e) {
                    System.err.println("[ExternalToolLoader] Error al cargar tool: " +
                            e.getMessage());
                }
            }
            if (loaded > 0) {
                System.out.println("[EtherBrain] " + loaded +
                        " tools externas cargadas desde " + file);
            }
            return loaded;

        } catch (Exception e) {
            System.err.println("[ExternalToolLoader] No se pudo leer " + file +
                    ": " + e.getMessage());
            return 0;
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static ExternalTool parse(JsonNode def) {
        String name = require(def, "name");
        String description = require(def, "description");

        JsonNode schemaNode = def.path("input_schema");
        if (schemaNode.isMissingNode()) {
            throw new IllegalArgumentException("tool '" + name + "' no tiene input_schema");
        }
        String inputSchema = schemaNode.toString();

        JsonNode commandNode = def.path("command");
        if (!commandNode.isArray() || commandNode.isEmpty()) {
            throw new IllegalArgumentException("tool '" + name + "' no tiene command (array)");
        }
        List<String> command = new ArrayList<>();
        for (JsonNode token : commandNode) command.add(token.asText());

        String outputStr = def.path("output").asText("stdout");
        ExternalTool.OutputMode mode = "file".equalsIgnoreCase(outputStr)
                ? ExternalTool.OutputMode.FILE
                : ExternalTool.OutputMode.STDOUT;

        return new ExternalTool(name, description, inputSchema, command, mode);
    }

    private static String require(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.asText().isBlank()) {
            throw new IllegalArgumentException("Campo requerido ausente: " + field);
        }
        return v.asText();
    }

    // ── Resolución de archivo ─────────────────────────────────────────────────

    private static Path resolveFile() {
        String explicit = System.getenv("AGENT_TOOLS_FILE");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.exists(p)) return p;
            System.err.println("[ExternalToolLoader] AGENT_TOOLS_FILE no existe: " + p);
            return null;
        }
        // Buscar tools.json en el directorio de trabajo
        Path cwd = Path.of("tools.json");
        if (Files.exists(cwd)) return cwd;
        return null;
    }
}
