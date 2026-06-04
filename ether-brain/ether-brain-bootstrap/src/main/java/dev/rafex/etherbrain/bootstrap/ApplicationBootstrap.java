package dev.rafex.etherbrain.bootstrap;

import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.policy.DefaultRetryPolicy;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.runtime.AgentLoop;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.core.runtime.AgentTool;
import dev.rafex.etherbrain.core.runtime.LocalAgentRegistry;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import dev.rafex.etherbrain.ports.runtime.AgentRegistry;
import dev.rafex.ether.logging.core.config.LoggingConfigurator;
import dev.rafex.etherbrain.infra.file.FileSessionStore;
import dev.rafex.etherbrain.infra.http.HttpModelClient;
import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.infra.http.ProviderCodec;
import dev.rafex.etherbrain.infra.http.codec.AnthropicCodec;
import dev.rafex.etherbrain.infra.http.codec.BedrockCodec;
import dev.rafex.etherbrain.infra.http.codec.GeminiCodec;
import dev.rafex.etherbrain.infra.http.codec.OpenAiCodec;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.RemoteServiceConfig;
import dev.rafex.etherbrain.ports.session.SessionStore;
import dev.rafex.etherbrain.tools.local.CurrentTimeTool;
import dev.rafex.etherbrain.tools.local.EchoTool;
import dev.rafex.etherbrain.tools.local.ExternalToolLoader;
import dev.rafex.etherbrain.tools.local.InMemoryToolRegistry;
import dev.rafex.etherbrain.ports.memory.MemoryProvider;
import dev.rafex.etherbrain.tools.remote.FaissMemoryProvider;
import dev.rafex.etherbrain.tools.remote.FaissTokenManager;
import dev.rafex.etherbrain.tools.remote.KnowledgeSearchTool;
import dev.rafex.etherbrain.tools.remote.MemoryCommitTool;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Assembles the EtherBrain runtime from environment variables.
 *
 * <h2>Modelo LLM — tres variables universales</h2>
 * <pre>
 * LLM_URL    — endpoint completo del proveedor
 * LLM_TOKEN  — API key o token (vacío si no se requiere, ej. Ollama local)
 * LLM_MODEL  — nombre del modelo
 * </pre>
 *
 * <p>El tipo de API se indica con {@code LLM_TYPE}. Existen 4 formatos reales en el mercado:
 * <pre>
 * LLM_TYPE     Codec               Proveedores
 * ──────────────────────────────────────────────────────────────────────
 * openai       OpenAiCodec ✅      OpenAI, Groq, Deepseek, Mistral, Qwen,
 *                                  OpenRouter, Together AI, Fireworks,
 *                                  Ollama, LM Studio, vLLM y cualquier
 *                                  endpoint /v1/chat/completions
 * anthropic    AnthropicCodec ✅   Anthropic Claude (API directa o proxy)
 * gemini       GeminiCodec ⏳      Google Gemini (pendiente de implementar)
 * bedrock      BedrockCodec ⏳     AWS Bedrock (pendiente de implementar)
 * </pre>
 * <p>Si {@code LLM_TYPE} no está definido se intenta inferir del path de la URL
 * como fallback, y si tampoco coincide se usa {@code openai} por defecto.
 *
 * <p>Ejemplos:
 * <pre>
 * # Claude (Anthropic)
 * LLM_URL=https://api.anthropic.com/v1/messages
 * LLM_TOKEN=sk-ant-...
 * LLM_MODEL=claude-opus-4-5
 *
 * # Groq
 * LLM_URL=https://api.groq.com/openai/v1/chat/completions
 * LLM_TOKEN=gsk_...
 * LLM_MODEL=llama-3.3-70b-versatile
 *
 * # Deepseek
 * LLM_URL=https://api.deepseek.com/v1/chat/completions
 * LLM_TOKEN=sk-...
 * LLM_MODEL=deepseek-chat
 *
 * # OpenRouter (cualquier modelo vía un solo endpoint)
 * LLM_URL=https://openrouter.ai/api/v1/chat/completions
 * LLM_TOKEN=sk-or-...
 * LLM_MODEL=anthropic/claude-opus-4-5
 *
 * # Ollama local (sin token)
 * LLM_URL=http://localhost:11434/v1/chat/completions
 * LLM_MODEL=llama3.2
 * </pre>
 *
 * <h2>Base de conocimiento (faiss-poc)</h2>
 * <pre>
 * FAISS_BASE_URL      — URL del servicio faiss-poc
 * FAISS_EMAIL         — email para login automático (+ FAISS_PASSWORD)
 * FAISS_PASSWORD      — contraseña para login automático
 * FAISS_AUTH_TOKEN    — token JWT estático (alternativa)
 * FAISS_API_KEY       — API key estática (alternativa)
 * FAISS_SKIP_TLS_VERIFY — "true" para certificados autofirmados
 * </pre>
 *
 * <h2>Sesiones y logs</h2>
 * <pre>
 * SESSION_DIR — directorio para sesiones persistentes (omitir = en memoria)
 * LOG_LEVEL   — OFF | SEVERE | WARNING | INFO (default) | FINE | ALL
 * </pre>
 */
public final class ApplicationBootstrap {

    /** Shared agent registry — available to all tools via static accessor. */
    private static final LocalAgentRegistry AGENT_REGISTRY = new LocalAgentRegistry();

    /** Returns the global agent registry (read-only after bootstrap). */
    public static AgentRegistry agentRegistry() { return AGENT_REGISTRY; }

    public AgentRuntime bootstrap() {
        loadDotEnv();         // carga .env antes de leer cualquier variable
        configureLogging();

        // LLM_TIMEOUT_SECONDS — leído aquí para pasar a ModelClient y AgentConfig
        long timeoutSecs0 = parseLong(env("LLM_TIMEOUT_SECONDS", "60"), 60);
        ModelClient modelClient = buildModelClient(java.time.Duration.ofSeconds(timeoutSecs0));
        SessionStore sessionStore = buildSessionStore();

        InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry()
                .register(new EchoTool())
                .register(new CurrentTimeTool());

        Set<String> enabledTools = new HashSet<>(Set.of("echo", "current_time"));

        // ── Tools externas: JSON file (subprocess, http, mcp) ────────────────────
        ExternalToolLoader.load(toolRegistry);

        // ── Tools SPI: JARs en el classpath con META-INF/services ────────────────
        ExternalToolLoader.loadFromClasspath(toolRegistry);

        // Habilitar todas las tools registradas (nativas + externas + SPI)
        toolRegistry.all().stream()
                .map(t -> t.name())
                .forEach(enabledTools::add);
        Map<String, RemoteServiceConfig> remoteServices = new HashMap<>();

        // ── faiss-poc (activado si FAISS_BASE_URL está definido) ─────────────
        String faissUrl = System.getenv("FAISS_BASE_URL");
        boolean skipTls = "true".equalsIgnoreCase(env("FAISS_SKIP_TLS_VERIFY", "false"));
        MemoryProvider memoryProvider = null;

        if (faissUrl != null && !faissUrl.isBlank()) {
            TokenProvider faissToken = buildFaissTokenProvider(faissUrl, skipTls);
            if (faissToken == null) {
                System.err.println("[EtherBrain] FAISS_BASE_URL definido pero sin credenciales. " +
                        "Configura FAISS_EMAIL+FAISS_PASSWORD, FAISS_AUTH_TOKEN o FAISS_API_KEY.");
            } else {
                // RAG — búsqueda en documentos permanentes (v1)
                String defaultNs = env("FAISS_DEFAULT_NAMESPACE", null);
                toolRegistry.register(new KnowledgeSearchTool(faissToken, skipTls, defaultNs));
                enabledTools.add("knowledge_search");
                remoteServices.put(KnowledgeSearchTool.SERVICE_NAME,
                        RemoteServiceConfig.of(KnowledgeSearchTool.SERVICE_NAME, faissUrl, ""));
                System.out.println("[EtherBrain] knowledge_search (RAG v1) → " + faissUrl);

                // Memoria de agente (v2) — scratchpad + commit a largo plazo
                String memoryNs = env("FAISS_MEMORY_NAMESPACE",
                                      defaultNs != null ? defaultNs : null);
                if (memoryNs != null && !memoryNs.isBlank()) {
                    int sessionTtl = (int) parseLong(env("FAISS_SESSION_TTL_MINUTES", "1440"), 1440);
                    FaissMemoryProvider fmp = new FaissMemoryProvider(
                            faissUrl, memoryNs, faissToken, skipTls, sessionTtl);
                    memoryProvider = fmp;

                    // MemoryCommitTool: el modelo decide qué promover a largo plazo
                    toolRegistry.register(
                            new MemoryCommitTool(faissUrl, memoryNs, faissToken, fmp, skipTls));
                    enabledTools.add("memory_commit");
                    System.out.println("[EtherBrain] memoria (v2) → namespace=" + memoryNs +
                            " ttl=" + sessionTtl + "m");
                } else {
                    System.out.println("[EtherBrain] memoria v2 desactivada — " +
                            "define FAISS_MEMORY_NAMESPACE para activarla");
                }
            }
        }

        // AGENT_SYSTEM_PROMPT — system prompt personalizado (opcional)
        String systemPrompt = env("AGENT_SYSTEM_PROMPT", AgentConfig.DEFAULT_SYSTEM_PROMPT);

        int maxSteps = (int) parseLong(env("AGENT_MAX_STEPS", "8"), 8);
        java.time.Duration timeout = java.time.Duration.ofSeconds(
                parseLong(env("LLM_TIMEOUT_SECONDS", "60"), 60));

        AgentConfig agentConfig = new AgentConfig(
                maxSteps, timeout,
                Set.copyOf(enabledTools),
                Map.copyOf(remoteServices),
                systemPrompt);

        // ── Retry policy ──────────────────────────────────────────────────────
        RetryPolicy retryPolicy = buildRetryPolicy();

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine(),
                retryPolicy
        );

        // ── Agent name and description ────────────────────────────────────────
        String agentName = env("AGENT_NAME", "agent");
        String agentDesc = env("AGENT_DESCRIPTION",
                "AI agent powered by EtherBrain. Delegates complex sub-tasks when needed.");

        AgentRuntime runtime = new AgentRuntime(
                sessionStore, agentLoop, agentConfig, memoryProvider, agentName, agentDesc);

        // ── Register this agent in the global registry ────────────────────────
        AGENT_REGISTRY.register(runtime);

        // ── Sub-agents from AGENT_SUB_* env vars ─────────────────────────────
        // AGENT_SUB_0=name:description — future extension point (stub for now)
        // Full multi-agent config requires each sub-agent to have its own
        // LLM_URL, etc. See docs/multi-agent.md for topology patterns.

        // ── Register sub-agents already in registry as tools ─────────────────
        // (populated by external callers before bootstrap finishes)
        AGENT_REGISTRY.all().stream()
                .filter(r -> !r.agentName().equals(agentName))  // skip self
                .forEach(subAgent -> {
                    toolRegistry.register(new AgentTool(subAgent));
                    enabledTools.add(subAgent.agentName());
                    System.out.println("[EtherBrain] sub-agent tool: " + subAgent.agentName());
                });

        return runtime;
    }

    /**
     * Register a sub-agent before calling {@link #bootstrap()} so it is
     * automatically exposed as a tool to the main agent.
     *
     * <pre>{@code
     * AgentRuntime researcher = new ApplicationBootstrap().buildSubAgent(...);
     * ApplicationBootstrap.registerSubAgent(researcher);
     * AgentRuntime orchestrator = new ApplicationBootstrap().bootstrap();
     * }</pre>
     */
    public static void registerSubAgent(AgentRuntime subAgent) {
        AGENT_REGISTRY.register(subAgent);
    }

    /** @deprecated Use {@link #bootstrap()} — reads configuration from environment. */
    @Deprecated
    public AgentRuntime bootstrapForDemo() {
        return bootstrap();
    }

    // ── Model client ──────────────────────────────────────────────────────────

    private static ModelClient buildModelClient(java.time.Duration timeout) {
        String llmUrl   = System.getenv("LLM_URL");
        String llmToken = env("LLM_TOKEN", "");
        String llmModel = env("LLM_MODEL", "");

        if (llmUrl == null || llmUrl.isBlank()) {
            System.err.println("[EtherBrain] LLM_URL no definida — modo demo (sin LLM real).");
            return new DemoModelClient();
        }
        if (llmModel.isBlank()) {
            throw new IllegalStateException(
                    "LLM_URL está definida pero falta LLM_MODEL.");
        }

        // timeout unificado: mismo valor para HTTP y para el Future del loop
        HttpModelConfig config = new HttpModelConfig(
                URI.create(llmUrl), llmToken, llmModel, 4096, timeout);

        ProviderCodec codec = resolveCodec(llmUrl);
        String llmType = env("LLM_TYPE", "openai");
        System.out.println("[EtherBrain] LLM → " + llmUrl +
                " | tipo: " + llmType + " | modelo: " + llmModel);
        return new HttpModelClient(config, codec);
    }

    /**
     * Resuelve el codec con esta cadena de prioridad:
     * <ol>
     *   <li>{@code LLM_TYPE} explícito (recomendado)</li>
     *   <li>Inferencia desde el hostname de {@code LLM_URL} (fallback)</li>
     *   <li>{@code OpenAiCodec} como default</li>
     * </ol>
     *
     * <p>Valores válidos de {@code LLM_TYPE}:
     * {@code openai} | {@code anthropic} | {@code gemini} | {@code bedrock}.
     */
    private static ProviderCodec resolveCodec(String llmUrl) {
        String llmType = env("LLM_TYPE", "").toLowerCase();

        // 1 — LLM_TYPE explícito
        if (!llmType.isBlank()) {
            return switch (llmType) {
                case "openai"    -> new OpenAiCodec();
                case "anthropic" -> new AnthropicCodec();
                case "gemini"    -> new GeminiCodec();
                case "bedrock"   -> new BedrockCodec();
                default -> throw new IllegalArgumentException(
                        "LLM_TYPE=\"" + llmType + "\" no reconocido. " +
                        "Valores válidos: openai | anthropic | gemini | bedrock");
            };
        }

        // 2 — inferencia por hostname (LLM_URL es URL base, no tiene path)
        String host = llmUrl.toLowerCase();
        if (host.contains("anthropic.com")) {
            warn("LLM_TYPE no definido — inferido 'anthropic' del hostname. Añade LLM_TYPE=anthropic.");
            return new AnthropicCodec();
        }
        if (host.contains("generativelanguage.googleapis.com") ||
            host.contains("aiplatform.googleapis.com")) {
            warn("LLM_TYPE no definido — inferido 'gemini' del hostname. Añade LLM_TYPE=gemini.");
            return new GeminiCodec();
        }
        if (host.contains("amazonaws.com")) {
            warn("LLM_TYPE no definido — inferido 'bedrock' del hostname. Añade LLM_TYPE=bedrock.");
            return new BedrockCodec();
        }

        // 3 — default: OpenAI-compatible (cubre la mayoría del mercado)
        return new OpenAiCodec();
    }

    private static void warn(String msg) {
        System.err.println("[EtherBrain] AVISO: " + msg);
    }

    // ── Session store ─────────────────────────────────────────────────────────

    private static SessionStore buildSessionStore() {
        String dir = System.getenv("SESSION_DIR");
        if (dir == null || dir.isBlank()) return new InMemorySessionStore();

        // SESSION_TTL_HOURS — horas de vida de una sesión (0 = sin límite)
        long ttlHours = parseLong(env("SESSION_TTL_HOURS", "0"), 0);
        java.time.Duration ttl = ttlHours > 0
                ? java.time.Duration.ofHours(ttlHours)
                : FileSessionStore.NO_TTL;
        return new FileSessionStore(Path.of(dir), ttl);
    }

    // ── faiss-poc auth ────────────────────────────────────────────────────────

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

    // ── Logging ───────────────────────────────────────────────────────────────

    private static void configureLogging() {
        String level = env("LOG_LEVEL", "INFO");
        try {
            LoggingConfigurator.configureRootLogger(Level.parse(level.toUpperCase()));
        } catch (IllegalArgumentException e) {
            LoggingConfigurator.configureRootLogger(Level.INFO);
        }
    }

    // ── .env loader ──────────────────────────────────────────────────────────

    /**
     * Carga un archivo {@code .env} en {@code System.setProperty()}.
     *
     * <p>Prioridad: variables de entorno reales del SO siempre ganan sobre
     * las del archivo. El archivo es solo un fallback para desarrollo local.
     *
     * <p>Ubicaciones buscadas en orden:
     * <ol>
     *   <li>Variable {@code ENV_FILE} — ruta explícita al archivo</li>
     *   <li>{@code .env} en el directorio de trabajo actual</li>
     *   <li>{@code ../.env} (útil cuando se ejecuta desde un submódulo Maven)</li>
     * </ol>
     *
     * <p>Formato del archivo:
     * <pre>
     * # comentario
     * LLM_TYPE=anthropic
     * LLM_URL=https://api.anthropic.com/v1/messages
     * LLM_TOKEN=sk-ant-...
     * LLM_MODEL=claude-haiku-4-5
     * </pre>
     */
    public static void loadDotEnv() {
        Path envFile = resolveEnvFile();
        if (envFile == null) return;

        try {
            int loaded = 0;
            for (String line : Files.readAllLines(envFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key   = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();

                // Quitar comillas opcionales: "valor" o 'valor'
                if (value.length() >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                     (value.startsWith("'")  && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }

                // Las variables reales del SO tienen prioridad
                if (System.getenv(key) == null) {
                    System.setProperty(key, value);
                    loaded++;
                }
            }
            if (loaded > 0) {
                System.out.println("[EtherBrain] .env cargado: " + envFile +
                        " (" + loaded + " variables)");
            }
        } catch (IOException e) {
            System.err.println("[EtherBrain] No se pudo leer .env: " + envFile + " — " + e.getMessage());
        }
    }

    private static Path resolveEnvFile() {
        // 1. ENV_FILE explícito
        String explicit = System.getenv("ENV_FILE");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.exists(p)) return p;
            System.err.println("[EtherBrain] ENV_FILE definido pero no existe: " + p);
            return null;
        }
        // 2. .env en el directorio actual
        Path cwd = Path.of(".env");
        if (Files.exists(cwd)) return cwd;
        // 3. ../.env (cuando se ejecuta desde un submódulo Maven)
        Path parent = Path.of("../.env");
        if (Files.exists(parent)) return parent;
        return null;
    }

    // ── Retry policy ─────────────────────────────────────────────────────────

    /**
     * Builds the tool retry policy from environment variables:
     * <pre>
     * AGENT_RETRY_MAX      — max retries per tool (default: 0 = no retry)
     * AGENT_RETRY_DELAY_MS — ms between retries (default: 500)
     * </pre>
     */
    private static RetryPolicy buildRetryPolicy() {
        int  maxRetries = (int) parseLong(env("AGENT_RETRY_MAX",      "0"),   0);
        long delayMs    =       parseLong(env("AGENT_RETRY_DELAY_MS", "500"), 500);
        if (maxRetries > 0) {
            System.out.println("[EtherBrain] retry policy: max=" + maxRetries
                    + " delay=" + delayMs + "ms");
        }
        return new DefaultRetryPolicy(maxRetries, delayMs);
    }

    // ── Env helpers ───────────────────────────────────────────────────────────

    /**
     * Lee una variable de entorno. Orden de prioridad:
     * <ol>
     *   <li>Variable real del SO ({@code System.getenv})</li>
     *   <li>Propiedad del sistema ({@code System.getProperty}) — cargada desde {@code .env}</li>
     *   <li>{@code defaultValue}</li>
     * </ol>
     */
    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        value = System.getProperty(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    // ── Demo client (sin LLM real) ────────────────────────────────────────────

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
