package dev.rafex.etherbrain.tools.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool genérica que ejecuta un comando externo (subprocess).
 *
 * <p>Permite exponer cualquier CLI como tool del agente sin escribir código Java.
 * La definición viene de un archivo JSON cargado por {@link ExternalToolLoader}.
 *
 * <h2>Plantillas en el comando</h2>
 * Los argumentos del modelo se sustituyen en la lista {@code command}:
 * <ul>
 *   <li>{@code ${nombre_arg}} — sustituido por el valor del argumento</li>
 *   <li>{@code ${__output__}} — sustituido por la ruta de un archivo temporal;
 *       EtherBrain lo crea, el proceso escribe ahí y EtherBrain lee el resultado</li>
 * </ul>
 *
 * <h2>Modos de captura de resultado</h2>
 * <ul>
 *   <li>{@code "output":"stdout"} — captura stdout del proceso</li>
 *   <li>{@code "output":"file"}   — lee el archivo {@code ${__output__}}</li>
 * </ul>
 *
 * <h2>Ejemplo de definición (tools.json)</h2>
 * <pre>{@code
 * [
 *   {
 *     "name": "ocr_document",
 *     "description": "Extracts text from a PDF or image using ether-ocr (OCR capable).",
 *     "input_schema": {
 *       "type": "object",
 *       "properties": {
 *         "file_path": {
 *           "type": "string",
 *           "description": "Absolute path to the PDF or image file"
 *         }
 *       },
 *       "required": ["file_path"]
 *     },
 *     "command": ["ether-ocr", "ocr", "${file_path}", "${__output__}"],
 *     "output": "file"
 *   }
 * ]
 * }</pre>
 */
public final class ExternalTool implements Tool {

    private final String       name;
    private final String       description;
    private final String       inputSchema;
    private final List<String> commandTemplate;
    private final OutputMode   outputMode;
    private final ObjectMapper mapper = new ObjectMapper();

    public enum OutputMode { STDOUT, FILE }

    public ExternalTool(String name, String description, String inputSchema,
                         List<String> commandTemplate, OutputMode outputMode) {
        this.name            = name;
        this.description     = description;
        this.inputSchema     = inputSchema;
        this.commandTemplate = List.copyOf(commandTemplate);
        this.outputMode      = outputMode;
    }

    @Override public String name()        { return name; }
    @Override public String description() { return description; }
    @Override public String inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        JsonNode args = mapper.readTree(arguments == null ? "{}" : arguments);

        // Preparar archivo de salida temporal si el modo es FILE
        Path tmpOutput = outputMode == OutputMode.FILE
                ? Files.createTempFile("etherbrain-ext-", ".txt")
                : null;

        try {
            // Sustituir plantillas en el comando
            List<String> cmd = resolveCommand(args, tmpOutput);

            // Ejecutar proceso
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            applyEnv(pb);

            Process proc = pb.start();
            String  stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int     exit   = proc.waitFor();

            if (exit != 0) {
                return new ToolResult(name, false,
                        "Command exited with code " + exit + ":\n" + stdout.strip());
            }

            // Leer resultado
            String result = switch (outputMode) {
                case STDOUT -> stdout.strip();
                case FILE   -> {
                    if (tmpOutput == null || !Files.exists(tmpOutput)) {
                        yield "Command succeeded but produced no output file.";
                    }
                    yield Files.readString(tmpOutput, StandardCharsets.UTF_8).strip();
                }
            };

            if (result.isBlank()) {
                return new ToolResult(name, false, "Command produced no output.");
            }
            return new ToolResult(name, true, result);

        } finally {
            if (tmpOutput != null) Files.deleteIfExists(tmpOutput);
        }
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private List<String> resolveCommand(JsonNode args, Path tmpOutput) {
        List<String> resolved = new ArrayList<>();
        for (String part : commandTemplate) {
            String token = part;
            // Sustituir ${__output__}
            if (tmpOutput != null) {
                token = token.replace("${__output__}", tmpOutput.toAbsolutePath().toString());
            }
            // Sustituir ${nombre_arg} con el valor del argumento
            if (token.contains("${")) {
                for (var field : (Iterable<Map.Entry<String, JsonNode>>) args::fields) {
                    token = token.replace("${" + field.getKey() + "}",
                            field.getValue().asText());
                }
            }
            resolved.add(token);
        }
        return resolved;
    }

    /** Aplica variables de entorno adicionales definidas con el prefijo TOOL_{NAME}_ENV_. */
    private void applyEnv(ProcessBuilder pb) {
        String prefix = "TOOL_" + name.toUpperCase().replace("-", "_") + "_ENV_";
        System.getenv().forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                pb.environment().put(k.substring(prefix.length()), v);
            }
        });
    }
}
