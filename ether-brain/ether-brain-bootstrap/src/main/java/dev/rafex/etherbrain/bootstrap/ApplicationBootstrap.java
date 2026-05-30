package dev.rafex.etherbrain.bootstrap;

import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.runtime.AgentLoop;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.ether.logging.core.config.LoggingConfigurator;
import dev.rafex.etherbrain.infra.file.FileSessionStore;
import dev.rafex.etherbrain.infra.http.HttpModelClient;
import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.infra.http.codec.AnthropicCodec;
import dev.rafex.etherbrain.infra.http.codec.OpenAiCodec;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.RemoteServiceConfig;
import dev.rafex.etherbrain.ports.session.SessionStore;
import dev.rafex.etherbrain.tools.local.CurrentTimeTool;
import dev.rafex.etherbrain.tools.local.EchoTool;
import dev.rafex.etherbrain.tools.local.InMemoryToolRegistry;
import dev.rafex.etherbrain.tools.remote.FaissTokenManager;
import dev.rafex.etherbrain.tools.remote.KnowledgeSearchTool;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Assembles the EtherBrain runtime from environment variables.
 *
 * <h2>Model provider</h2>
 * <pre>
 * MODEL_PROVIDER    — "anthropic" | "openai" | "openai-compatible" | "demo" (default)
 * MODEL_NAME        — model identifier forwarded to the provider
 * ANTHROPIC_API_KEY — required when MODEL_PROVIDER=anthropic
 * OPENAI_API_KEY    — required when MODEL_PROVIDER=openai or openai-compatible
 * OPENAI_BASE_URL   — required when MODEL_PROVIDER=openai-compatible
 * </pre>
 *
 * <h2>Knowledge base (faiss-poc)</h2>
 * <pre>
 * FAISS_BASE_URL       — base URL of the faiss-poc service (e.g. https://51.161.11.83:8443)
 *
 * Auth (choose one):
 *   FAISS_EMAIL + FAISS_PASSWORD  — auto-login + token refresh (recommended)
 *   FAISS_AUTH_TOKEN              — static JWT (expires after 60 min, no auto-refresh)
 *   FAISS_API_KEY                 — static API key (never expires)
 *
 * FAISS_SKIP_TLS_VERIFY — "true" to accept self-signed certificates (default: false)
 * </pre>
 *
 * <h2>Session persistence</h2>
 * <pre>
 * SESSION_DIR — directory for file-backed sessions; omit for in-memory
 * LOG_LEVEL   — OFF | SEVERE | WARNING | INFO (default) | FINE | ALL
 * </pre>
 */
public final class ApplicationBootstrap {

    public AgentRuntime bootstrap() {
        configureLogging();

        ModelClient modelClient = buildModelClient();
        SessionStore sessionStore = buildSessionStore();

        InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry()
                .register(new EchoTool())
                .register(new CurrentTimeTool());

        Set<String> enabledTools = new HashSet<>(Set.of("echo", "current_time"));
        Map<String, RemoteServiceConfig> remoteServices = new HashMap<>();

        // ── faiss-poc wiring (activado si FAISS_BASE_URL está definido) ───────
        String faissUrl      = System.getenv("FAISS_BASE_URL");
        boolean skipTls      = "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false"));

        if (faissUrl != null && !faissUrl.isBlank()) {
            TokenProvider faissToken = buildFaissTokenProvider(faissUrl, skipTls);
            if (faissToken == null) {
                System.err.println("[EtherBrain] FAISS_BASE_URL definido pero sin credenciales. " +
                        "Configura FAISS_EMAIL+FAISS_PASSWORD, FAISS_AUTH_TOKEN o FAISS_API_KEY.");
            } else {
                toolRegistry.register(new KnowledgeSearchTool(faissToken, skipTls));
                enabledTools.add("knowledge_search");
                // baseUri se almacena en RemoteServiceConfig; el token lo gestiona TokenProvider
                remoteServices.put(KnowledgeSearchTool.SERVICE_NAME,
                        RemoteServiceConfig.of(KnowledgeSearchTool.SERVICE_NAME, faissUrl, ""));
                System.out.println("[EtherBrain] knowledge_search habilitado → " + faissUrl +
                        (skipTls ? " (TLS verify: OFF)" : ""));
            }
        }

        AgentConfig agentConfig = AgentConfig.defaults(
                Set.copyOf(enabledTools),
                Map.copyOf(remoteServices));

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        return new AgentRuntime(sessionStore, agentLoop, agentConfig);
    }

    /** @deprecated Use {@link #bootstrap()} — reads configuration from environment. */
    @Deprecated
    public AgentRuntime bootstrapForDemo() {
        return bootstrap();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static ModelClient buildModelClient() {
        String provider = env("MODEL_PROVIDER", "demo").toLowerCase();

        return switch (provider) {
            case "anthropic" -> {
                String key = requireEnv("ANTHROPIC_API_KEY", "MODEL_PROVIDER=anthropic");
                String model = env("MODEL_NAME", "claude-opus-4-5");
                yield new HttpModelClient(HttpModelConfig.anthropic(key, model), new AnthropicCodec());
            }
            case "openai" -> {
                String key = requireEnv("OPENAI_API_KEY", "MODEL_PROVIDER=openai");
                String model = env("MODEL_NAME", "gpt-4o-mini");
                yield new HttpModelClient(HttpModelConfig.openAi(key, model), new OpenAiCodec());
            }
            case "openai-compatible" -> {
                String baseUrl = requireEnv("OPENAI_BASE_URL", "MODEL_PROVIDER=openai-compatible");
                String key = env("OPENAI_API_KEY", "");
                String model = requireEnv("MODEL_NAME", "MODEL_PROVIDER=openai-compatible");
                yield new HttpModelClient(
                        HttpModelConfig.openAiCompatible(URI.create(baseUrl), key, model),
                        new OpenAiCodec());
            }
            default -> {
                System.err.println("[EtherBrain] MODEL_PROVIDER not set or unrecognised " +
                        "(\"" + provider + "\") — using demo client. " +
                        "Set MODEL_PROVIDER=anthropic|openai|openai-compatible for a real LLM.");
                yield new DemoModelClient();
            }
        };
    }

    private static SessionStore buildSessionStore() {
        String dir = System.getenv("SESSION_DIR");
        if (dir != null && !dir.isBlank()) {
            return new FileSessionStore(Path.of(dir));
        }
        return new InMemorySessionStore();
    }

    /**
     * Builds the best available auth provider for faiss-poc.
     * Priority: credentials (auto-refresh) > static JWT > static API key.
     * Returns null if no auth is configured.
     */
    private static TokenProvider buildFaissTokenProvider(String baseUrl, boolean skipTls) {
        String email    = System.getenv("FAISS_EMAIL");
        String password = System.getenv("FAISS_PASSWORD");
        if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
            System.out.println("[EtherBrain] faiss-poc auth: login automático (" + email + ")");
            return new FaissTokenManager(URI.create(baseUrl), email, password, skipTls);
        }

        String staticToken = env("FAISS_AUTH_TOKEN", System.getenv("FAISS_API_KEY"));
        if (staticToken != null && !staticToken.isBlank()) {
            System.out.println("[EtherBrain] faiss-poc auth: token estático");
            return () -> staticToken;
        }

        return null;
    }

    private static void configureLogging() {
        String level = env("LOG_LEVEL", "INFO");
        try {
            LoggingConfigurator.configureRootLogger(Level.parse(level.toUpperCase()));
        } catch (IllegalArgumentException e) {
            LoggingConfigurator.configureRootLogger(Level.INFO);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static String requireEnv(String name, String context) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable '" + name +
                    "' (needed when " + context + ")");
        }
        return value;
    }

    // ── Demo client (no real LLM) ─────────────────────────────────────────────

    static final class DemoModelClient implements ModelClient {

        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL);

            if (hasToolResult) {
                String content = request.messages().stream()
                        .filter(m -> m.role() == Message.Role.TOOL)
                        .reduce((a, b) -> b)
                        .map(Message::content)
                        .orElse("(empty)");
                return new FinalAnswer("Resultado de tool: " + content);
            }

            Message latest = request.messages().getLast();
            String content = latest.content().toLowerCase();
            if (content.contains("time") || content.contains("hora")) {
                return new ToolRequest("current_time", "{}");
            }
            return new ToolRequest("echo", latest.content());
        }
    }
}
