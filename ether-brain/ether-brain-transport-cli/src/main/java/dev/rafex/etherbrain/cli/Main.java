package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.bootstrap.SpiModelBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI entry-point for EtherBrain.
 *
 * <h2>Conversación</h2>
 * <pre>
 * java -jar ether-brain-cli.jar "¿Quién eres?"
 * java -jar ether-brain-cli.jar --session s1 "¿Quién eres?"
 * java -jar ether-brain-cli.jar                          # REPL interactivo
 * </pre>
 *
 * <h2>Upload de documentos al knowledge base (faiss-poc)</h2>
 * <pre>
 * java -jar ether-brain-cli.jar upload documento.pdf --namespace mi-ns
 * java -jar ether-brain-cli.jar upload nota.md --namespace mi-ns --tags java,arch
 * java -jar ether-brain-cli.jar upload *.txt --namespace mi-ns
 * </pre>
 * Formatos soportados: PDF (extracción automática), TXT, MD, y cualquier texto UTF-8.
 * Requiere: FAISS_BASE_URL, FAISS_EMAIL + FAISS_PASSWORD (o FAISS_AUTH_TOKEN).
 *
 * <h2>Modo SPI (ServiceLoader)</h2>
 * <pre>
 * java -jar ether-brain-cli.jar --list-providers
 * java -jar ether-brain-cli.jar --provider openai "¿Quién eres?"
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {

        // ── upload: subir documentos al knowledge base ────────────────────────
        if (args.length > 0 && "upload".equals(args[0])) {
            runUpload(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

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
            AgentRuntime runtime = SpiModelBootstrap.bootstrap(provider, buildSpiConfig());
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

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Procesa el subcomando {@code upload}.
     *
     * <pre>
     * upload &lt;archivo&gt; [&lt;archivo2&gt; ...] --namespace &lt;ns&gt; [--tags tag1,tag2]
     * </pre>
     */
    private static void runUpload(String[] args) throws Exception {
        String namespace = env("FAISS_DEFAULT_NAMESPACE", null);
        List<String> tags  = new ArrayList<>();
        List<File>   files = new ArrayList<>();

        // Parsear argumentos
        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--namespace", "-n" -> namespace = args[++i];
                case "--tags", "-t"      -> tags.addAll(Arrays.asList(args[++i].split(",")));
                default                  -> files.add(new File(args[i]));
            }
            i++;
        }

        if (files.isEmpty()) {
            System.err.println("Uso: upload <archivo> [--namespace <ns>] [--tags tag1,tag2]");
            System.err.println("     Formatos: .pdf, .txt, .md, .java, .xml, .json, .csv …");
            System.exit(1);
        }

        if (namespace == null || namespace.isBlank()) {
            System.err.println("[upload] ERROR: define --namespace o FAISS_DEFAULT_NAMESPACE");
            System.exit(1);
        }

        // Credenciales faiss-poc
        String faissUrl = env("FAISS_BASE_URL", null);
        if (faissUrl == null || faissUrl.isBlank()) {
            System.err.println("[upload] ERROR: FAISS_BASE_URL no definido");
            System.exit(1);
        }

        String token    = obtainFaissToken(faissUrl);
        boolean skipTls = "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false"));

        DocumentUploader uploader = new DocumentUploader(faissUrl, token, skipTls);

        int ok = 0, fail = 0;
        for (File file : files) {
            try {
                String result = uploader.upload(file, namespace, tags);
                System.out.println("[✓] " + file.getName() + " → " + result);
                ok++;
            } catch (Exception e) {
                System.err.println("[✗] " + file.getName() + " → " + e.getMessage());
                fail++;
            }
        }

        System.out.printf("%nResultado: %d subidos, %d errores%n", ok, fail);
        if (fail > 0) System.exit(1);
    }

    /** Obtiene un token JWT del faiss-poc usando email/password o token estático. */
    private static String obtainFaissToken(String faissUrl) throws Exception {
        // Token estático (FAISS_AUTH_TOKEN o FAISS_API_KEY)
        String token = env("FAISS_AUTH_TOKEN", env("FAISS_API_KEY", null));
        if (token != null && !token.isBlank()) return token;

        // Login automático con email/password
        String email    = env("FAISS_EMAIL", null);
        String password = env("FAISS_PASSWORD", null);
        if (email == null || password == null) {
            throw new IllegalStateException(
                    "Define FAISS_AUTH_TOKEN o bien FAISS_EMAIL + FAISS_PASSWORD");
        }

        // POST /auth/token
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        boolean skip = "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false"));

        var client  = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        // TLS skip si hace falta
        if (skip) {
            // Reutilizamos DocumentUploader que ya tiene la lógica TLS
        }

        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(faissUrl.replaceAll("/+$","") + "/api/v1/auth/token"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(15))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();

        var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Login falló HTTP " + resp.statusCode() + ": " + resp.body());
        }

        // Extraer access_token del JSON sin librería externa
        String responseBody = resp.body();
        var m = java.util.regex.Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(responseBody);
        if (!m.find()) throw new RuntimeException("No se encontró access_token en: " + responseBody);
        return m.group(1);
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
            if (k.startsWith("OPENAI_") || k.startsWith("OLLAMA_")) config.put(k, v);
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

    // ── Env helper ────────────────────────────────────────────────────────────

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
