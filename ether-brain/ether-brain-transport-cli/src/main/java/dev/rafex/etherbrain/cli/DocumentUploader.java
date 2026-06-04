package dev.rafex.etherbrain.cli;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Sube documentos al knowledge base de faiss-poc.
 *
 * <h2>Formatos soportados</h2>
 * <ul>
 *   <li><b>PDF</b>  — extrae texto con Apache PDFBox</li>
 *   <li><b>TXT / MD / cualquier texto UTF-8</b> — lee directamente</li>
 * </ul>
 *
 * <h2>Uso desde el CLI</h2>
 * <pre>
 * java -jar ether-brain-cli.jar upload --namespace mi-ns documento.pdf
 * java -jar ether-brain-cli.jar upload --namespace mi-ns --tags java,arch nota.md
 * java -jar ether-brain-cli.jar upload --namespace mi-ns *.txt
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

    /**
     * Sube un archivo al namespace indicado.
     *
     * @param file      archivo a subir (.pdf, .txt, .md, etc.)
     * @param namespace nombre del namespace en faiss-poc
     * @param tags      etiquetas opcionales
     * @return respuesta del servidor (JSON)
     */
    /**
     * Sube un archivo. Usa multipart/form-data (más eficiente que base64 JSON).
     * El texto se extrae del archivo antes de enviarlo — solo texto UTF-8 llega al servidor.
     */
    public String upload(File file, String namespace, List<String> tags) throws Exception {
        if (!file.exists()) throw new IOException("Archivo no encontrado: " + file);

        String text = extractText(file);
        if (text.isBlank()) throw new IOException("El archivo no contiene texto extraíble: " + file.getName());

        System.out.printf("[upload] %s → %d caracteres extraídos%n",
                file.getName(), text.length());

        // Multipart boundary
        String boundary = "--------EtherBrainBoundary" + System.currentTimeMillis();
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Construir body multipart manualmente (sin deps extra)
        String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" +
                file.getName() + "\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n";

        StringBuilder tagParts = new StringBuilder();
        for (String tag : tags) {
            tagParts.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"tags\"\r\n\r\n")
                    .append(tag).append("\r\n");
        }
        String closing = "--" + boundary + "--\r\n";

        byte[] header  = partHeader.getBytes(StandardCharsets.UTF_8);
        byte[] between = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] tagData = tagParts.toString().getBytes(StandardCharsets.UTF_8);
        byte[] end     = closing.getBytes(StandardCharsets.UTF_8);

        // Concatenar partes
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(header);
        bos.write(textBytes);
        bos.write(between);
        bos.write(tagData);
        bos.write(end);
        byte[] multipartBody = bos.toByteArray();

        String url = baseUrl + "/api/v1/namespaces/" + namespace + "/upload/multipart";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            return resp.body();
        }
        throw new IOException("Upload falló HTTP " + resp.statusCode() + ": " + resp.body());
    }

    // ── Extracción de texto ───────────────────────────────────────────────────

    /** Extrae texto del archivo según su extensión. */
    static String extractText(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            return extractPdf(file);
        }
        // Texto plano: txt, md, java, xml, json, yaml, csv, etc.
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String extractPdf(File file) throws Exception {
        // Usamos PDFBox 3.x via reflexión para no forzar import si no está en classpath
        try {
            Class<?> loaderClass = Class.forName("org.apache.pdfbox.Loader");
            Class<?> docClass    = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Class<?> stripperClass = Class.forName("org.apache.pdfbox.text.PDFTextStripper");

            Object doc      = loaderClass.getMethod("loadPDF", File.class).invoke(null, file);
            Object stripper = stripperClass.getDeclaredConstructor().newInstance();
            String text     = (String) stripperClass.getMethod("getText", docClass).invoke(stripper, doc);
            docClass.getMethod("close").invoke(doc);
            return text;

        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "PDFBox no está disponible en el classpath. " +
                    "Extrae el texto manualmente y sube el .txt resultante.", e);
        }
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
