package dev.rafex.etherbrain.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.memory.MemoryProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Implementación de {@link MemoryProvider} sobre la API v2 de faiss-poc.
 *
 * <h2>Flujo de memoria híbrida</h2>
 * <pre>
 * Por cada turno del agente:
 *   recall()  → POST /api/v2/namespaces/{ns}/sessions/{sid}/query
 *               (búsqueda dual: scratchpad FAISS + pgvector)
 *               → contexto inyectado en el system prompt
 *
 *   remember() → POST /api/v2/namespaces/{ns}/sessions/{sid}/memory
 *                (guarda "User: ... \n Assistant: ..." en el scratchpad)
 * </pre>
 *
 * <h2>Sesiones faiss-poc</h2>
 * Cada sesión EtherBrain tiene su propia sesión faiss-poc (creada lazy).
 * El mapa se mantiene en memoria — si el proceso reinicia, las nuevas sesiones
 * de faiss-poc se crean automáticamente.
 *
 * <h2>Commit manual</h2>
 * El modelo puede invocar {@link MemoryCommitTool} para promover contexto
 * del scratchpad a pgvector permanente.
 */
public final class FaissMemoryProvider implements MemoryProvider {

    private final String baseUrl;
    private final String namespace;
    private final TokenProvider tokenProvider;
    private final boolean skipTlsVerify;
    private final int sessionTtlMinutes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    /** EtherBrain sessionId → faiss-poc sessionId */
    private final ConcurrentHashMap<String, String> sessionMap = new ConcurrentHashMap<>();

    public FaissMemoryProvider(String baseUrl,
                                String namespace,
                                TokenProvider tokenProvider,
                                boolean skipTlsVerify,
                                int sessionTtlMinutes) {
        this.baseUrl          = baseUrl.replaceAll("/+$", "");
        this.namespace        = namespace;
        this.tokenProvider    = tokenProvider;
        this.skipTlsVerify    = skipTlsVerify;
        this.sessionTtlMinutes = sessionTtlMinutes;
        this.httpClient       = buildHttpClient(skipTlsVerify);
    }

    // ── MemoryProvider ────────────────────────────────────────────────────────

    @Override
    public String recall(String sessionId, String query) throws Exception {
        String faissSessionId = getOrCreateSession(sessionId);
        if (faissSessionId == null) return null;

        String url = baseUrl + "/api/v2/namespaces/" + namespace +
                     "/sessions/" + faissSessionId + "/query";

        String body = mapper.writeValueAsString(Map.of(
                "query",            query,
                "k_session",        5,
                "k_knowledge",      5,
                "weight_session",   0.4,
                "weight_knowledge", 0.6,
                "normalize_scores", true
        ));

        HttpResponse<String> resp = post(url, body);
        if (resp.statusCode() != 200) return null;

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) return null;

        // Formatear los resultados más relevantes
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (JsonNode item : results) {
            String text  = item.path("text").asText(item.path("chunk").asText(""));
            double score = item.path("score").asDouble(0);
            if (text.isBlank() || score < 0.3) continue;
            if (count++ >= 3) break;      // máximo 3 fragmentos
            sb.append("- ").append(text.strip()).append("\n");
        }
        return sb.isEmpty() ? null : sb.toString().strip();
    }

    @Override
    public void remember(String sessionId, String userMessage, String agentAnswer) throws Exception {
        String faissSessionId = getOrCreateSession(sessionId);
        if (faissSessionId == null) return;

        String url = baseUrl + "/api/v2/namespaces/" + namespace +
                     "/sessions/" + faissSessionId + "/memory";

        String turn = "User: " + userMessage + "\nAssistant: " + agentAnswer;
        String body = mapper.writeValueAsString(Map.of("texts", List.of(turn)));

        HttpResponse<String> resp = post(url, body);
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new RuntimeException("Memory add failed HTTP " + resp.statusCode() +
                                       ": " + resp.body());
        }
    }

    // ── Gestión de sesiones faiss-poc ─────────────────────────────────────────

    /**
     * Devuelve el faiss-poc sessionId para esta sesión EtherBrain,
     * creándolo si no existe todavía.
     */
    public String getOrCreateSession(String etherbrainSessionId) {
        return sessionMap.computeIfAbsent(etherbrainSessionId, id -> {
            try {
                return createFaissSession();
            } catch (Exception e) {
                System.err.println("[FaissMemory] No se pudo crear sesión: " + e.getMessage());
                return null;
            }
        });
    }

    private String createFaissSession() throws Exception {
        String url = baseUrl + "/api/v2/namespaces/" + namespace + "/sessions";
        String body = mapper.writeValueAsString(Map.of("ttl_minutes", sessionTtlMinutes));

        HttpResponse<String> resp = post(url, body);
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new RuntimeException("Create session HTTP " + resp.statusCode() +
                                       ": " + resp.body());
        }

        JsonNode json = mapper.readTree(resp.body());
        String sid = json.path("session_id").asText(json.path("id").asText(""));
        if (sid.isBlank()) {
            throw new RuntimeException("No session_id in response: " + resp.body());
        }
        System.out.println("[FaissMemory] Sesión creada: " + sid +
                           " (namespace=" + namespace + ", ttl=" + sessionTtlMinutes + "m)");
        return sid;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private HttpResponse<String> post(String url, String body) throws Exception {
        String token = tokenProvider.getToken();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));

        if (token != null && token.startsWith("eyJ")) {
            req.header("Authorization", "Bearer " + token);
        } else if (token != null && !token.isBlank()) {
            req.header("X-API-Key", token);
        }

        return httpClient.send(
                req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    private static HttpClient buildHttpClient(boolean skipTlsVerify) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (skipTlsVerify) {
            try {
                TrustManager[] trustAll = {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAll, new SecureRandom());
                builder.sslContext(ctx);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build trust-all SSL context", e);
            }
        }
        return builder.build();
    }
}
