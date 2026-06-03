package dev.rafex.etherbrain.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Tool that allows the model to commit important context from the session
 * scratchpad to permanent long-term storage (pgvector).
 *
 * <p>Use this when the conversation has produced something worth remembering
 * across future sessions — e.g. user preferences, key facts, decisions made.
 *
 * <h2>Input schema</h2>
 * <pre>{@code
 * {
 *   "summary": "The user prefers Java 21 and hexagonal architecture",
 *   "label":   "preferences-session-2026-06-02",   // optional filename in pgvector
 *   "k":       5                                    // how many scratchpad chunks to commit
 * }
 * }</pre>
 */
public final class MemoryCommitTool implements Tool {

    private final String baseUrl;
    private final String namespace;
    private final TokenProvider tokenProvider;
    private final FaissMemoryProvider memoryProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public MemoryCommitTool(String baseUrl,
                             String namespace,
                             TokenProvider tokenProvider,
                             FaissMemoryProvider memoryProvider,
                             boolean skipTlsVerify) {
        this.baseUrl        = baseUrl.replaceAll("/+$", "");
        this.namespace      = namespace;
        this.tokenProvider  = tokenProvider;
        this.memoryProvider = memoryProvider;
        this.httpClient     = buildHttpClient(skipTlsVerify);
    }

    @Override
    public String name() { return "memory_commit"; }

    @Override
    public String description() {
        return "Commits important context from the current session's working memory to " +
               "permanent long-term storage. Use this when the conversation contains " +
               "information worth remembering in future sessions: user preferences, " +
               "key facts, decisions, or important context discovered during this session.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "summary": {
                      "type": "string",
                      "description": "Brief description of what is being committed and why it is important"
                    },
                    "label": {
                      "type": "string",
                      "description": "Optional label/filename for the committed document in long-term storage"
                    },
                    "k": {
                      "type": "integer",
                      "description": "Number of scratchpad chunks to commit (1-20, default 5)",
                      "default": 5
                    }
                  },
                  "required": ["summary"]
                }
                """;
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        JsonNode args    = mapper.readTree(arguments);
        String summary   = args.path("summary").asText();
        String label     = args.path("label").isMissingNode()
                ? "session-" + context.sessionId() + "-commit"
                : args.path("label").asText();
        int k            = args.path("k").isMissingNode() ? 5 : args.path("k").asInt(5);

        if (summary.isBlank()) {
            return new ToolResult(name(), false, "Error: summary must not be blank");
        }

        // Get the faiss-poc session ID for this EtherBrain session
        String faissSessionId = memoryProvider.getOrCreateSession(context.sessionId());
        if (faissSessionId == null) {
            return new ToolResult(name(), false,
                    "No active memory session for session: " + context.sessionId());
        }

        String url = baseUrl + "/api/v2/namespaces/" + namespace +
                     "/sessions/" + faissSessionId + "/memory/commit";

        String body = mapper.writeValueAsString(Map.of(
                "query", summary,
                "k",     k,
                "label", label
        ));

        String token = tokenProvider.getToken();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));

        if (token != null && token.startsWith("eyJ")) {
            req.header("Authorization", "Bearer " + token);
        } else if (token != null && !token.isBlank()) {
            req.header("X-API-Key", token);
        }

        HttpResponse<String> resp = httpClient.send(
                req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            return new ToolResult(name(), false,
                    "Commit failed HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode result = mapper.readTree(resp.body());
        int committed = result.path("committed").asInt(
                result.path("chunks_committed").asInt(k));

        return new ToolResult(name(), true,
                "Committed " + committed + " memory chunks to long-term storage. " +
                "Label: \"" + label + "\". " +
                "This context will be available in future sessions.");
    }

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
