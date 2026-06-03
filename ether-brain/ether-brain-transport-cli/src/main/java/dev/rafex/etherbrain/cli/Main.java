package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.bootstrap.SpiModelBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI entry-point for EtherBrain.
 *
 * <h2>Modo estándar (ApplicationBootstrap)</h2>
 * <pre>
 * # Turno único
 * java -jar ether-brain-cli.jar "¿Quién eres?"
 *
 * # Sesión con nombre (persiste historial)
 * java -jar ether-brain-cli.jar --session s1 "¿Quién eres?"
 *
 * # REPL interactivo
 * java -jar ether-brain-cli.jar
 * </pre>
 *
 * <h2>Modo SPI (ServiceLoader)</h2>
 * <pre>
 * # Listar proveedores disponibles en classpath
 * java -jar ether-brain-cli.jar --list-providers
 *
 * # Turno único con proveedor SPI explícito
 * java -jar ether-brain-cli.jar --provider openai "¿Quién eres?"
 * java -jar ether-brain-cli.jar --provider ollama "¿Quién eres?"
 * </pre>
 *
 * <p>Configuración vía variables de entorno o {@code .env}. Ver
 * {@link ApplicationBootstrap} y {@link SpiModelBootstrap}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {

        // ── --list-providers: listar proveedores SPI disponibles ──────────────
        if (args.length > 0 && "--list-providers".equals(args[0])) {
            listProviders();
            return;
        }

        // ── --provider <name>: usar proveedor SPI explícito ───────────────────
        if (args.length > 0 && "--provider".equals(args[0])) {
            String provider = args.length > 1 ? args[1] : "demo";
            String input = args.length > 2
                    ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                    : "What time is it?";
            Map<String, String> config = buildSpiConfig();
            AgentRuntime runtime = SpiModelBootstrap.bootstrap(provider, config);
            System.out.println(runtime.run("cli-session", input));
            return;
        }

        // ── Modo estándar: ApplicationBootstrap con --session y REPL ─────────
        String sessionId = "cli-session";
        String[] remaining = args;

        if (args.length >= 2 && "--session".equals(args[0])) {
            sessionId = args[1];
            remaining = Arrays.copyOfRange(args, 2, args.length);
        }

        AgentRuntime runtime = new ApplicationBootstrap().bootstrap();

        if (remaining.length > 0) {
            System.out.println(runtime.run(sessionId, String.join(" ", remaining)));
        } else {
            runRepl(runtime, sessionId);
        }
    }

    // ── SPI helpers ───────────────────────────────────────────────────────────

    private static void listProviders() {
        List<ProviderMetadata> providers = SpiModelBootstrap.listProviders();
        if (providers.isEmpty()) {
            System.out.println("No hay proveedores SPI en el classpath.");
            return;
        }
        System.out.println("Proveedores disponibles (--provider <name>):");
        for (ProviderMetadata meta : providers) {
            System.out.printf("  %-12s — %s%n", meta.name(), meta.description());
        }
    }

    private static Map<String, String> buildSpiConfig() {
        Map<String, String> config = new HashMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("OPENAI_") || k.startsWith("OLLAMA_")) {
                config.put(k, v);
            }
        });
        return config;
    }

    // ── REPL ─────────────────────────────────────────────────────────────────

    private static void runRepl(AgentRuntime runtime, String sessionId) {
        System.out.println("EtherBrain — escribe 'exit' para salir | sesión: " + sessionId);
        System.out.println("─".repeat(60));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) break;

                try {
                    System.out.println(runtime.run(sessionId, line));
                } catch (Exception e) {
                    System.err.println("[Error] " + e.getMessage());
                }
            }
        }
        System.out.println("Goodbye.");
    }
}
