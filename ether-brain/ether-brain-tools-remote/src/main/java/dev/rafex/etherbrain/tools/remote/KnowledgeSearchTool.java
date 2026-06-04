package dev.rafex.etherbrain.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.common.ToolExecutionException;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.runtime.RemoteServiceConfig;
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
 * Performs semantic search against a faiss-poc knowledge base.
 *
 * <p>Auth is delegated to a {@link TokenProvider}:
 * <ul>
 *   <li>{@link FaissTokenManager} — automatic JWT login + refresh (recommended)</li>
 *   <li>Lambda {@code () -> "my-api-key"} — static key or token</li>
 * </ul>
 *
 * <p>Requires a {@link RemoteServiceConfig} named {@value #SERVICE_NAME} in
 * {@code AgentConfig} for the {@code baseUri}:
 * <pre>{@code
 * AgentConfig.defaults(
 *     Set.of("knowledge_search"),
 *     Map.of("faiss-poc",
 *            RemoteServiceConfig.of("faiss-poc", "https://vps:8443", ""))
 * );
 * }</pre>
 *
 * <h2>Input schema</h2>
 * <pre>{@code { "query": "...", "namespace": "...", "limit": 5 } }</pre>
 */
public final class KnowledgeSearchTool implements Tool {

    public static final String SERVICE_NAME = "faiss-poc";

    private final TokenProvider tokenProvider;
    private final boolean skipTlsVerify;
    /** Namespace usado cuando el modelo no especifica uno. Null = obligatorio. */
    private final String defaultNamespace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    /**
     * @param tokenProvider    auth token source (static or dynamic)
     * @param skipTlsVerify    {@code true} to accept self-signed certificates
     * @param defaultNamespace namespace por defecto si el modelo no proporciona uno;
     *                         {@code null} o vacío para requerir que el modelo lo especifique
     */
    public KnowledgeSearchTool(TokenProvider tokenProvider,
                                boolean skipTlsVerify,
                                String defaultNamespace) {
        this.tokenProvider    = tokenProvider;
        this.skipTlsVerify    = skipTlsVerify;
        this.defaultNamespace = (defaultNamespace != null && !defaultNamespace.isBlank())
                ? defaultNamespace : null;
        this.httpClient = buildHttpClient(skipTlsVerify);
    }

    /** Backward-compatible: sin namespace por defecto. */
    public KnowledgeSearchTool(TokenProvider tokenProvider, boolean skipTlsVerify) {
        this(tokenProvider, skipTlsVerify, null);
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        String base = "Searches the knowledge base for content semantically similar to the query. " +
                      "Use this before answering questions that require specific knowledge or facts.";
        return defaultNamespace != null
                ? base + " Default namespace: \"" + defaultNamespace + "\"."
                : base;
    }

    @Override
    public String inputSchema() {
        boolean namespaceRequired = defaultNamespace == null;
        String defaultHint = defaultNamespace != null
                ? ", \"default\": \"" + defaultNamespace + "\""
                : "";
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "Text to search for semantically in the knowledge base"
                    },
                    "namespace": {
                      "type": "string",
                      "description": "Knowledge base namespace to search in"\
                """ + defaultHint + """

                    },
                    "limit": {
                      "type": "integer",
                      "description": "Maximum number of results (1-20)",
                      "default": 5
                    },
                    "use_rerank": {
                      "type": "boolean",
                      "description": "Use cross-encoder reranking for higher precision (slower, ~500ms extra). Use when result quality matters more than speed.",
                      "default": false
                    },
                    "use_bm25": {
                      "type": "boolean",
                      "description": "Combine semantic search with BM25 keyword search for better recall on exact terms.",
                      "default": false
                    }
                  },
                  "required": [\
                """ + (namespaceRequired ? "\"query\", \"namespace\"" : "\"query\"") + """
                ]
                }
                """;
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        JsonNode args = mapper.readTree(arguments);
        String query     = args.path("query").asText();
        String namespace = args.path("namespace").isMissingNode() || args.path("namespace").asText().isBlank()
                ? defaultNamespace
                : args.path("namespace").asText();
        int     limit     = args.path("limit").isMissingNode() ? 5 : args.path("limit").asInt(5);
        boolean useRerank = args.path("use_rerank").asBoolean(false);
        boolean useBm25   = args.path("use_bm25").asBoolean(false);

        if (query.isBlank()) {
            return new ToolResult(name(), false, "Error: query must not be blank");
        }
        if (namespace == null || namespace.isBlank()) {
            return new ToolResult(name(), false,
                    "Error: namespace is required. " +
                    "Specify it in the call or set FAISS_DEFAULT_NAMESPACE.");
        }

        RemoteServiceConfig cfg = context.agentConfig()
                .remoteService(SERVICE_NAME)
                .orElseThrow(() -> new ToolExecutionException(
                        "Remote service '" + SERVICE_NAME + "' not configured. " +
                        "Add RemoteServiceConfig.of(\"faiss-poc\", \"https://vps:8443\", \"\") " +
                        "to AgentConfig."));

        return searchWithRetry(query, namespace, limit, useRerank, useBm25, cfg, true);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private ToolResult searchWithRetry(String query, String namespace, int limit,
                                       boolean useRerank, boolean useBm25,
                                       RemoteServiceConfig cfg,
                                       boolean firstAttempt) throws Exception {
        String token = tokenProvider.getToken();
        String base  = cfg.baseUri().toString().stripTrailing();
        String url   = base + "/api/v1/namespaces/" + namespace + "/search";

        String body = mapper.writeValueAsString(Map.of(
                "query",            query,
                "k",                Math.min(limit * 2, 50),
                "limit",            limit,
                "offset",           0,
                "normalize_scores", true,
                "use_rerank",       useRerank,
                "use_bm25",         useBm25
        ));

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));

        // JWT (eyJ…) → Bearer; anything else → X-API-Key
        if (token != null && token.startsWith("eyJ")) {
            req.header("Authorization", "Bearer " + token);
        } else {
            req.header("X-API-Key", token);
        }

        HttpResponse<String> response =
                httpClient.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 && firstAttempt) {
            // Token was rejected — invalidate and retry once
            tokenProvider.invalidate();
            return searchWithRetry(query, namespace, limit, useRerank, useBm25, cfg, false);
        }

        if (response.statusCode() != 200) {
            return new ToolResult(name(), false,
                    "Search returned HTTP " + response.statusCode() +
                    ". Body: " + response.body());
        }

        JsonNode result = mapper.readTree(response.body());
        return new ToolResult(name(), true, formatResults(result, namespace));
    }

    private String formatResults(JsonNode result, String namespace) {
        JsonNode items = result.path("items");
        int total = result.path("total").asInt(0);

        if (!items.isArray() || items.isEmpty()) {
            return "No results found in namespace '" + namespace + "'.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(total)
          .append(" result(s) in namespace '").append(namespace).append("':\n\n");

        int i = 1;
        for (JsonNode item : items) {
            String filename = item.path("filename").asText("unknown");
            String chunk    = item.path("chunk").asText("");
            double score    = item.path("score").asDouble(0);

            sb.append(i++).append(". [").append(filename)
              .append("] (score: ").append(String.format("%.3f", score)).append(")\n")
              .append(chunk.strip()).append("\n\n");
        }
        return sb.toString().strip();
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
