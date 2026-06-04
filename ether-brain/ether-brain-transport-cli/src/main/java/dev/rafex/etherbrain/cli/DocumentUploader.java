package dev.rafex.etherbrain.cli;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Sube documentos al knowledge base de faiss-poc.
 *
 * <h2>Formatos soportados</h2>
 * <ul>
 *   <li><b>PDF, PNG, JPG, TIFF (escaneados)</b> — delega a {@code ether-ocr} para OCR</li>
 *   <li><b>PDF con capa de texto</b> — delega a {@code ether-ocr} con extracción directa</li>
 *   <li><b>TXT, MD y cualquier texto UTF-8</b> — lectura directa</li>
 * </ul>
 *
 * <h2>Configuración de ether-ocr</h2>
 * La extracción se delega al comando {@code ether-ocr}. Se busca en este orden:
 * <ol>
 *   <li>Variable de entorno {@code ETHER_OCR_CMD} (ruta explícita)</li>
 *   <li>{@code ether-ocr} en el PATH del sistema</li>
 *   <li>{@code python3 -m ether_ocr} con {@code ETHER_OCR_PYTHONPATH}</li>
 * </ol>
 *
 * <h2>Uso desde el CLI</h2>
 * <pre>
 * java -jar ether-brain-cli.jar upload --namespace mi-ns documento.pdf
 * java -jar ether-brain-cli.jar upload --namespace mi-ns --tags java,arch nota.md
 * java -jar ether-brain-cli.jar upload --namespace mi-ns *.pdf
 * </pre>
 */
public final class DocumentUploader {

    private final String baseUrl;
    private final String token;
    private final boolean skipTlsVerify;
    private final HttpClient httpClient;

    public DocumentUploader(String baseUrl, String token, boolean skipTlsVerify) {
        this.baseUrl       = baseUrl.replaceAll("/+$", "");
        this.token         = token;
        this.skipTlsVerify = skipTlsVerify;
        this.httpClient    = buildHttpClient(skipTlsVerify);
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public String upload(File file, String namespace, List<String> tags) throws Exception {
        if (!file.exists()) throw new IOException("Archivo no encontrado: " + file);

        String text = extractText(file);
        if (text.isBlank()) throw new IOException(
                "El archivo no contiene texto extraíble: " + file.getName());

        System.out.printf("[upload] %s → %d caracteres%n", file.getName(), text.length());

        return sendMultipart(file.getName(), text, namespace, tags);
    }

    // ── Extracción de texto ───────────────────────────────────────────────────

    /**
     * Extrae texto del archivo.
     * PDFs e imágenes se procesan con ether-ocr.
     * El resto se lee directamente como UTF-8.
     */
    static String extractText(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf") || name.endsWith(".png") ||
            name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".tiff") || name.endsWith(".tif")) {
            return extractWithOcr(file);
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Llama a ether-ocr para extraer texto del archivo.
     *
     * <p>ether-ocr maneja automáticamente:
     * <ul>
     *   <li>PDFs con capa de texto → pdftotext (Poppler)</li>
     *   <li>PDFs escaneados e imágenes → Tesseract OCR</li>
     * </ul>
     */
    static String extractWithOcr(File file) throws Exception {
        Path tmpOut = Files.createTempFile("etherbrain-ocr-", ".txt");
        try {
            List<String> cmd = buildOcrCommand(file.getAbsolutePath(),
                                               tmpOut.toAbsolutePath().toString());

            System.out.printf("[ether-ocr] %s %s%n", cmd.get(0),
                    String.join(" ", cmd.subList(1, Math.min(cmd.size(), 5))));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String output  = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int    exit    = proc.waitFor();

            if (exit != 0) {
                throw new IOException(
                        "ether-ocr terminó con código " + exit + ":\n" + output.strip());
            }

            if (!Files.exists(tmpOut)) {
                throw new IOException("ether-ocr no generó archivo de salida. Output:\n" + output);
            }

            return Files.readString(tmpOut, StandardCharsets.UTF_8);

        } finally {
            Files.deleteIfExists(tmpOut);
        }
    }

    /**
     * Construye el comando para invocar ether-ocr.
     * Prioridad: ETHER_OCR_CMD → ether-ocr en PATH → python3 -m ether_ocr.
     */
    static List<String> buildOcrCommand(String inputPath, String outputPath) {
        List<String> cmd = new ArrayList<>();

        String explicit = System.getenv("ETHER_OCR_CMD");
        if (explicit != null && !explicit.isBlank()) {
            // ETHER_OCR_CMD=/usr/local/bin/ether-ocr
            cmd.add(explicit);
        } else if (commandExists("ether-ocr")) {
            cmd.add("ether-ocr");
        } else {
            // Fallback: python3 -m ether_ocr con PYTHONPATH opcional
            String pythonPath = System.getenv("ETHER_OCR_PYTHONPATH");
            ProcessBuilder pb = new ProcessBuilder("python3", "-m", "ether_ocr");
            if (pythonPath != null && !pythonPath.isBlank()) {
                pb.environment().put("PYTHONPATH", pythonPath);
            }
            cmd.add("python3");
            cmd.add("-m");
            cmd.add("ether_ocr");
        }

        // Usar 'ocr' que maneja ambos casos: texto directo y OCR
        cmd.add("ocr");
        cmd.add(inputPath);
        cmd.add(outputPath);

        // Idiomas: español + inglés por defecto, configurable
        String lang = System.getenv().getOrDefault("ETHER_OCR_LANG", "spa+eng");
        cmd.add("--lang");
        cmd.add(lang);

        return cmd;
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--help")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── HTTP multipart ────────────────────────────────────────────────────────

    private String sendMultipart(String filename, String text,
                                  String namespace, List<String> tags) throws Exception {
        String boundary  = "--------EtherBrainBoundary" + System.currentTimeMillis();
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" +
                filename + "\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n";

        StringBuilder tagParts = new StringBuilder();
        for (String tag : tags) {
            tagParts.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"tags\"\r\n\r\n")
                    .append(tag).append("\r\n");
        }
        String closing = "--" + boundary + "--\r\n";

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(partHeader.getBytes(StandardCharsets.UTF_8));
        bos.write(textBytes);
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(tagParts.toString().getBytes(StandardCharsets.UTF_8));
        bos.write(closing.getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/namespaces/" + namespace + "/upload/multipart"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200 || resp.statusCode() == 201) return resp.body();
        throw new IOException("Upload falló HTTP " + resp.statusCode() + ": " + resp.body());
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    private static HttpClient buildHttpClient(boolean skip) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (skip) {
            try {
                TrustManager[] all = {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, all, new SecureRandom());
                b.sslContext(ctx);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        return b.build();
    }
}
