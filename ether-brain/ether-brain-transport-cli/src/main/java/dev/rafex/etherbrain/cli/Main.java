package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.bootstrap.SpiModelBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

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
 * <h2>Upload de texto al knowledge base (faiss-poc)</h2>
 * Solo acepta texto UTF-8 (TXT, MD, etc.).
 * Para PDFs: primero extrae el texto con ether-ocr, luego sube el .txt.
 * <pre>
 * java -jar ether-brain-cli.jar upload nota.md --namespace mi-ns
 * java -jar ether-brain-cli.jar upload *.txt --namespace mi-ns --tags java,arch
 *
 * # Flujo recomendado para PDFs:
 * ether-ocr ocr documento.pdf documento.txt
 * java -jar ether-brain-cli.jar upload documento.txt --namespace mi-ns
 * </pre>
 *
 * <h2>Tools externas</h2>
 * Define tools en {@code tools.json} o {@code AGENT_TOOLS_FILE}.
 * El agente las descubre y usa automáticamente — sin código Java.
 * Ver: docs/tools-externas.md
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

        if (args.length > 0 && "upload".equals(args[0])) {
            runUpload(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length > 0 && "--list-providers".equals(args[0])) {
            listProviders();
            return;
        }

        if (args.length > 0 && "--provider".equals(args[0])) {
            String provider = args.length > 1 ? args[1] : "demo";
            String input    = args.length > 2
                    ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                    : "What time is it?";
            AgentRuntime runtime = SpiModelBootstrap.bootstrap(provider, buildSpiConfig());
            System.out.println(runtime.run("cli-session", input));
            return;
        }

        // ── Modo estándar ─────────────────────────────────────────────────────
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

    // ── Upload (solo texto UTF-8) ─────────────────────────────────────────────

    /**
     * Sube archivos de texto UTF-8 al knowledge base de faiss-poc.
     * No procesa PDFs — usa ether-ocr primero para convertirlos a texto.
     */
    private static void runUpload(String[] args) throws Exception {
        String       namespace = env("FAISS_DEFAULT_NAMESPACE", null);
        List<String> tags      = new ArrayList<>();
        List<File>   files     = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--namespace", "-n" -> namespace = args[++i];
                case "--tags",      "-t" -> tags.addAll(Arrays.asList(args[++i].split(",")));
                default                  -> files.add(new File(args[i]));
            }
            i++;
        }

        if (files.isEmpty()) {
            System.err.println("Uso: upload <archivo.txt> [--namespace <ns>] [--tags t1,t2]");
            System.err.println("     Solo acepta texto UTF-8 (TXT, MD, etc.).");
            System.err.println("     Para PDFs: ether-ocr ocr doc.pdf doc.txt && upload doc.txt");
            System.exit(1);
        }

        if (namespace == null || namespace.isBlank()) {
            System.err.println("[upload] ERROR: define --namespace o FAISS_DEFAULT_NAMESPACE");
            System.exit(1);
        }

        String  faissUrl = env("FAISS_BASE_URL", null);
        if (faissUrl == null || faissUrl.isBlank()) {
            System.err.println("[upload] ERROR: FAISS_BASE_URL no definido");
            System.exit(1);
        }

        String  token   = obtainFaissToken(faissUrl);
        boolean skipTls = "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false"));
        HttpClient http = buildHttpClient(skipTls);

        int ok = 0, fail = 0;
        for (File file : files) {
            try {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                sendMultipart(http, faissUrl, token, namespace, file.getName(), text, tags);
                System.out.printf("[✓] %s (%d chars)%n", file.getName(), text.length());
                ok++;
            } catch (Exception e) {
                System.err.println("[✗] " + file.getName() + " → " + e.getMessage());
                fail++;
            }
        }

        System.out.printf("%nResultado: %d subidos, %d errores%n", ok, fail);
        if (fail > 0) System.exit(1);
    }

    private static void sendMultipart(HttpClient http, String baseUrl, String token,
                                       String namespace, String filename, String text,
                                       List<String> tags) throws Exception {
        String boundary  = "--------EtherBrainBoundary" + System.currentTimeMillis();
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        String fileHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n";

        StringBuilder tagParts = new StringBuilder();
        for (String tag : tags) {
            tagParts.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"tags\"\r\n\r\n")
                    .append(tag).append("\r\n");
        }

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(fileHeader.getBytes(StandardCharsets.UTF_8));
        bos.write(textBytes);
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(tagParts.toString().getBytes(StandardCharsets.UTF_8));
        bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") +
                        "/api/v1/namespaces/" + namespace + "/upload/multipart"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private static String obtainFaissToken(String faissUrl) throws Exception {
        String token = env("FAISS_AUTH_TOKEN", env("FAISS_API_KEY", null));
        if (token != null && !token.isBlank()) return token;

        String email    = env("FAISS_EMAIL", null);
        String password = env("FAISS_PASSWORD", null);
        if (email == null || password == null) {
            throw new IllegalStateException("Define FAISS_AUTH_TOKEN o FAISS_EMAIL+FAISS_PASSWORD");
        }

        HttpClient client = buildHttpClient(
                "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false")));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(faissUrl.replaceAll("/+$", "") + "/api/v1/auth/token"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Login HTTP " + resp.statusCode() + ": " + resp.body());
        }

        var m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
        if (!m.find()) throw new RuntimeException("No access_token en respuesta: " + resp.body());
        return m.group(1);
    }

    private static HttpClient buildHttpClient(boolean skipTls) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (skipTls) {
            try {
                javax.net.ssl.TrustManager[] all = {new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }};
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, all, new java.security.SecureRandom());
                b.sslContext(ctx);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        return b.build();
    }

    // ── SPI helpers ───────────────────────────────────────────────────────────

    private static void listProviders() {
        List<ProviderMetadata> providers = SpiModelBootstrap.listProviders();
        if (providers.isEmpty()) { System.out.println("No hay proveedores SPI en el classpath."); return; }
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
                try { System.out.println(runtime.run(sessionId, line)); }
                catch (Exception e) { System.err.println("[Error] " + e.getMessage()); }
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
