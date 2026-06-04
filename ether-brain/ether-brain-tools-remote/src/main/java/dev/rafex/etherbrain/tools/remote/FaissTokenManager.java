package dev.rafex.etherbrain.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Dynamic JWT token provider for the faiss-poc service.
 *
 * <p>Calls {@code POST <baseUri>/auth/token} on the first request and caches
 * the resulting JWT. Refreshes proactively when less than 60 seconds remain
 * before expiry. Also supports forced refresh via {@link #invalidate()} when
 * a 401 is received mid-request.
 *
 * <p>Thread-safe: concurrent callers share a single token and a single
 * in-flight refresh (double-checked locking).
 *
 * <h2>TLS</h2>
 * Pass {@code skipTlsVerify = true} for servers with self-signed certificates.
 * Use {@code false} (or a proper CA certificate) in production.
 */
public final class FaissTokenManager implements TokenProvider {

    private static final Pattern EXP_PATTERN =
            Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
    /** Refresh 90 seconds before actual expiry to avoid races. */
    private static final long REFRESH_BUFFER_SECS = 90;

    private final URI baseUri;
    private final String email;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private final Object refreshLock = new Object();

    public FaissTokenManager(URI baseUri, String email, String password,
                             boolean skipTlsVerify) {
        this.baseUri  = baseUri;
        this.email    = email;
        this.password = password;
        this.httpClient = buildHttpClient(skipTlsVerify);
    }

    @Override
    public String getToken() throws Exception {
        if (isTokenValid()) {
            return cachedToken;
        }
        synchronized (refreshLock) {
            if (isTokenValid()) {          // re-check under lock
                return cachedToken;
            }
            return doLogin();
        }
    }

    @Override
    public void invalidate() {
        tokenExpiry = Instant.EPOCH;       // next call forces re-login
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private boolean isTokenValid() {
        return cachedToken != null
                && Instant.now().plusSeconds(REFRESH_BUFFER_SECS).isBefore(tokenExpiry);
    }

    private String doLogin() throws Exception {
        String url = baseUri.toString().stripTrailing() + "/api/v1/auth/token";

        String body = mapper.writeValueAsString(
                Map.of("email", email, "password", password));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "faiss-poc login failed (HTTP " + response.statusCode() +
                    "): " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        String token = json.path("access_token").asText();

        if (token == null || token.isBlank()) {
            throw new RuntimeException(
                    "faiss-poc login response missing access_token: " + response.body());
        }

        tokenExpiry = parseJwtExpiry(token);
        cachedToken = token;

        long ttlSecs = Duration.between(Instant.now(), tokenExpiry).getSeconds();
        System.out.printf("[FaissTokenManager] Token refreshed — expires in %ds%n", ttlSecs);
        return token;
    }

    // ── JWT exp parsing (no external lib needed) ─────────────────────────────

    static Instant parseJwtExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Instant.now().plusSeconds(3600);
            }
            // Base64URL decode the payload (add padding if necessary)
            String payload = parts[1];
            int rem = payload.length() % 4;
            if (rem > 0) payload = payload + "=".repeat(4 - rem);

            String json = new String(
                    Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);

            Matcher m = EXP_PATTERN.matcher(json);
            if (m.find()) {
                return Instant.ofEpochSecond(Long.parseLong(m.group(1)));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Instant.now().plusSeconds(3600);  // safe fallback
    }

    // ── TLS ──────────────────────────────────────────────────────────────────

    private static HttpClient buildHttpClient(boolean skipTlsVerify) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        if (skipTlsVerify) {
            try {
                TrustManager[] trustAll = {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
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
