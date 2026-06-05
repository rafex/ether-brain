package dev.rafex.etherbrain.bootstrap;

import dev.rafex.ether.logging.core.config.LoggingConfigurator;
import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.core.observability.LoggingMetricsCollector;
import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.policy.DefaultRetryPolicy;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.runtime.AgentLoop;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.core.runtime.AgentTool;
import dev.rafex.etherbrain.core.runtime.LocalAgentRegistry;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.auth.TokenProvider;
import dev.rafex.etherbrain.ports.memory.MemoryProvider;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.AgentRegistry;
import dev.rafex.etherbrain.ports.runtime.RemoteServiceConfig;
import dev.rafex.etherbrain.ports.session.SessionStore;
import dev.rafex.etherbrain.tools.local.CurrentTimeTool;
import dev.rafex.etherbrain.tools.local.EchoTool;
import dev.rafex.etherbrain.tools.local.ExternalToolLoader;
import dev.rafex.etherbrain.tools.local.InMemoryToolRegistry;
import dev.rafex.etherbrain.tools.remote.FaissMemoryProvider;
import dev.rafex.etherbrain.tools.remote.FaissTokenManager;
import dev.rafex.etherbrain.tools.remote.KnowledgeSearchTool;
import dev.rafex.etherbrain.tools.remote.MemoryCommitTool;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import dev.rafex.ether.logging.core.format.EtherLogFormatter;

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
 * gemini       GeminiCodec ✅      Google Gemini / AI Platform (streaming SSE)
 * bedrock      BedrockCodec ✅     AWS Bedrock (sin streaming nativo — fallback bloqueante)
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

    /**
     * Per-instance agent registry.
     *
     * <p>Changed from {@code static final} to an instance field so that:
     * <ul>
     *   <li>Multiple independent runtimes can co-exist in the same JVM (e.g. tests).</li>
     *   <li>Tests no longer share state between runs.</li>
     *   <li>The bootstrap is fully garbage-collectible when no longer needed.</li>
     * </ul>
     */
    private final LocalAgentRegistry agentRegistry = new LocalAgentRegistry();

    /** Returns this bootstrap's agent registry. */
    public AgentRegistry agentRegistry() { return agentRegistry; }

    public AgentRuntime bootstrap() {
        DotEnvLoader.load();   // carga .env antes de leer cualquier variable
        configureLogging();

        // LLM_TIMEOUT_SECONDS — leído aquí para pasar a ModelClient y AgentConfig
        long timeoutSecs0 = parseLong(env("LLM_TIMEOUT_SECONDS", "60"), 60);
        java.time.Duration timeout = java.time.Duration.ofSeconds(timeoutSecs0);

        ModelClient  modelClient  = ModelClientFactory.build(timeout);
        SessionStore sessionStore = SessionStoreFactory.build();

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
                EtherLog.warn(ApplicationBootstrap.class,
                        "FAISS_BASE_URL definido pero sin credenciales. " +
                        "Configura FAISS_EMAIL+FAISS_PASSWORD, FAISS_AUTH_TOKEN o FAISS_API_KEY.");
            } else {
                // RAG — búsqueda en documentos permanentes (v1)
                String defaultNs = env("FAISS_DEFAULT_NAMESPACE", null);
                toolRegistry.register(new KnowledgeSearchTool(faissToken, skipTls, defaultNs));
                enabledTools.add("knowledge_search");
                remoteServices.put(KnowledgeSearchTool.SERVICE_NAME,
                        RemoteServiceConfig.of(KnowledgeSearchTool.SERVICE_NAME, faissUrl, ""));
                EtherLog.info(ApplicationBootstrap.class,
                        "knowledge_search (RAG v1) → {}", faissUrl);

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
                    EtherLog.info(ApplicationBootstrap.class,
                            "memoria (v2) → namespace={} ttl={}m", memoryNs, sessionTtl);
                } else {
                    EtherLog.info(ApplicationBootstrap.class,
                            "memoria v2 desactivada — define FAISS_MEMORY_NAMESPACE para activarla");
                }
            }
        }

        // AGENT_SYSTEM_PROMPT — system prompt personalizado (opcional)
        String systemPrompt = env("AGENT_SYSTEM_PROMPT", AgentConfig.DEFAULT_SYSTEM_PROMPT);

        int maxSteps = (int) parseLong(env("AGENT_MAX_STEPS", "8"), 8);

        AgentConfig agentConfig = new AgentConfig(
                maxSteps, timeout,
                Set.copyOf(enabledTools),
                Map.copyOf(remoteServices),
                systemPrompt);

        // ── Retry policy + metrics ────────────────────────────────────────────
        RetryPolicy      retryPolicy = buildRetryPolicy();
        MetricsCollector metrics     = buildMetricsCollector();

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine(),
                retryPolicy,
                null,      // stepListener — injected per-request by HTTP transport
                metrics
        );

        // ── Agent name and description ────────────────────────────────────────
        String agentName = env("AGENT_NAME", "agent");
        String agentDesc = env("AGENT_DESCRIPTION",
                "AI agent powered by EtherBrain. Delegates complex sub-tasks when needed.");

        AgentRuntime runtime = new AgentRuntime(
                sessionStore, agentLoop, agentConfig, memoryProvider,
                agentName, agentDesc, metrics);

        // ── Register this agent in the global registry ────────────────────────
        agentRegistry.register(runtime);

        // ── Sub-agents from AGENT_SUB_* env vars ─────────────────────────────
        // AGENT_SUB_0=name:description — future extension point (stub for now)
        // Full multi-agent config requires each sub-agent to have its own
        // LLM_URL, etc. See docs/multi-agent.md for topology patterns.

        // ── Register sub-agents already in registry as tools ─────────────────
        // (populated by external callers before bootstrap finishes)
        agentRegistry.all().stream()
                .filter(r -> !r.agentName().equals(agentName))  // skip self
                .forEach(subAgent -> {
                    toolRegistry.register(new AgentTool(subAgent));
                    enabledTools.add(subAgent.agentName());
                    EtherLog.info(ApplicationBootstrap.class,
                            "sub-agent tool registrado: {}", subAgent.agentName());
                });

        EtherLog.info(ApplicationBootstrap.class,
                "Bootstrap completo — agente='{}' tools={} maxSteps={} timeout={}s",
                agentName, enabledTools.size(), maxSteps,
                timeout.toSeconds());

        return runtime;
    }

    /**
     * Registers a sub-agent before calling {@link #bootstrap()} so it is
     * automatically exposed as a tool to the main agent.
     *
     * <pre>{@code
     * ApplicationBootstrap bootstrap = new ApplicationBootstrap();
     * AgentRuntime researcher = buildResearcher();
     * bootstrap.registerSubAgent(researcher);          // instance method
     * AgentRuntime orchestrator = bootstrap.bootstrap();
     * }</pre>
     */
    public void registerSubAgent(AgentRuntime subAgent) {
        agentRegistry.register(subAgent);
    }

    /** @deprecated Use {@link #bootstrap()} — reads configuration from environment. */
    @Deprecated
    public AgentRuntime bootstrapForDemo() {
        return bootstrap();
    }

    // Model client and session store are built by ModelClientFactory and SessionStoreFactory.

    // ── faiss-poc auth ────────────────────────────────────────────────────────

    private static TokenProvider buildFaissTokenProvider(String baseUrl, boolean skipTls) {
        String email    = System.getenv("FAISS_EMAIL");
        String password = System.getenv("FAISS_PASSWORD");
        if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
            EtherLog.info(ApplicationBootstrap.class,
                    "faiss-poc auth: login automático ({})", email);
            return new FaissTokenManager(URI.create(baseUrl), email, password, skipTls);
        }
        String staticToken = env("FAISS_AUTH_TOKEN", System.getenv("FAISS_API_KEY"));
        if (staticToken != null && !staticToken.isBlank()) {
            EtherLog.info(ApplicationBootstrap.class, "faiss-poc auth: token estático");
            return () -> staticToken;
        }
        return null;
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    /**
     * Configura el sistema de logging con esta prioridad:
     * <ol>
     *   <li>Aplica niveles por paquete desde {@code logging.properties} en el
     *       classpath (silencia Jetty, Paho, Jackson…)</li>
     *   <li>Configura el root logger con el nivel de {@code LOG_LEVEL} y un
     *       {@code ConsoleHandler} formateado.</li>
     *   <li>Si {@code LOG_FILE} está definido, añade un {@code FileHandler}
     *       con rotación automática al root logger.</li>
     * </ol>
     *
     * <h2>Variables de entorno</h2>
     * <pre>
     * LOG_LEVEL           — OFF | SEVERE | WARNING | INFO | FINE | ALL (default: INFO)
     * LOG_FILE            — ruta del archivo de log, ej. /var/log/etherbrain/app.log
     *                       Soporta patrones JUL: %g (generación), %u (único), %h (home)
     * LOG_FILE_MAX_BYTES  — tamaño máximo por archivo antes de rotar (default: 10485760 = 10 MB)
     * LOG_FILE_COUNT      — número de archivos de rotación a conservar (default: 5)
     * LOG_FILE_APPEND     — true = continúa el archivo existente al reiniciar (default: true)
     * </pre>
     */
    private static void configureLogging() {
        // 1. Aplicar niveles por paquete desde logging.properties en classpath
        applyClasspathPackageLevels();

        // 2. Configurar ConsoleHandler con el nivel global
        String levelStr = env("LOG_LEVEL", "INFO");
        Level level;
        try {
            level = Level.parse(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
        }
        Logger root = LoggingConfigurator.configureRootLogger(level);

        // 3. Añadir FileHandler con rotación si LOG_FILE está definido
        String logFile = env("LOG_FILE", null);
        if (logFile != null && !logFile.isBlank()) {
            addFileHandler(root, logFile, level);
        }
    }

    /**
     * Lee {@code logging.properties} del classpath y aplica los niveles por
     * paquete ({@code nombre.del.paquete.level=NIVEL}) sin tocar handlers.
     *
     * <p>Este método se llama antes de {@code configureRootLogger} para que
     * los niveles de paquete sobrevivan aunque {@link LoggingConfigurator}
     * reemplace los handlers del root logger.
     */
    private static void applyClasspathPackageLevels() {
        try (InputStream is = ApplicationBootstrap.class
                .getClassLoader().getResourceAsStream("logging.properties")) {
            if (is == null) return;

            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                if (!key.endsWith(".level") || key.equals(".level")) continue;
                String loggerName = key.substring(0, key.length() - ".level".length());
                String levelName  = props.getProperty(key, "").trim();
                if (levelName.isBlank()) continue;
                try {
                    Logger.getLogger(loggerName).setLevel(Level.parse(levelName.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Nivel desconocido — ignorar silenciosamente
                }
            }
        } catch (IOException e) {
            // Si no se puede leer el archivo continuamos con los defaults
        }
    }

    /**
     * Añade un {@link FileHandler} con rotación automática al root logger.
     *
     * <p>El patrón del archivo acepta los especificadores de JUL:
     * <ul>
     *   <li>{@code %g} — número de generación (0, 1, 2…)</li>
     *   <li>{@code %u} — número único para evitar conflictos</li>
     *   <li>{@code %h} — directorio home del usuario</li>
     * </ul>
     *
     * <p>Si el patrón no contiene {@code %g} se añade automáticamente antes
     * de la extensión (o al final), para que la rotación genere nombres distintos.
     *
     * @param root    root logger al que añadir el handler
     * @param pattern ruta/patrón del archivo de log
     * @param level   nivel a aplicar al handler (igual que el root)
     */
    private static void addFileHandler(Logger root, String pattern, Level level) {
        // Asegurar que el patrón incluye %g para que la rotación produzca nombres distintos
        String resolvedPattern = pattern.contains("%g") ? pattern : addGenerationToken(pattern);

        // Crear el directorio padre si no existe
        try {
            Path dir = Paths.get(resolvedPattern.replaceAll("%[guh]", "0")).getParent();
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            EtherLog.warn(ApplicationBootstrap.class,
                    "No se pudo crear el directorio de logs '{}': {}", pattern, e.getMessage());
            return;
        }

        long   maxBytes = parseLong(env("LOG_FILE_MAX_BYTES", ""), 10 * 1024 * 1024); // 10 MB
        int    count    = (int) parseLong(env("LOG_FILE_COUNT",    ""), 5);
        boolean append  = !"false".equalsIgnoreCase(env("LOG_FILE_APPEND", "true"));

        try {
            FileHandler fh = new FileHandler(resolvedPattern, maxBytes, count, append);
            fh.setLevel(level);
            fh.setFormatter(new EtherLogFormatter());
            root.addHandler(fh);

            EtherLog.info(ApplicationBootstrap.class,
                    "FileHandler activado — archivo={} maxBytes={} rotaciones={} append={}",
                    resolvedPattern, maxBytes, count, append);
        } catch (IOException e) {
            EtherLog.warn(ApplicationBootstrap.class,
                    "No se pudo activar el FileHandler para '{}': {}", resolvedPattern, e.getMessage());
        }
    }

    /**
     * Inserta {@code .%g} antes de la extensión del archivo, o al final si no
     * tiene extensión. Así {@code /var/log/app.log} → {@code /var/log/app.%g.log}.
     */
    static String addGenerationToken(String pattern) {
        int dot = pattern.lastIndexOf('.');
        int sep = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
        // El punto debe ser parte del nombre del archivo, no de un directorio
        if (dot > sep) {
            return pattern.substring(0, dot) + ".%g" + pattern.substring(dot);
        }
        return pattern + ".%g";
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
    /** @see DotEnvLoader#load() */
    public static void loadDotEnv() { DotEnvLoader.load(); }

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
            EtherLog.info(ApplicationBootstrap.class,
                    "retry policy: max={} delay={}ms", maxRetries, delayMs);
        }
        return new DefaultRetryPolicy(maxRetries, delayMs);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /**
     * Builds the {@link MetricsCollector} from environment.
     *
     * <pre>
     * METRICS_ENABLED  — "true" (default) or "false" to disable entirely
     * </pre>
     *
     * <p>The default implementation logs each measurement as a structured line
     * via {@link EtherLog}, parseable by ELK, Loki, CloudWatch Logs Insights, etc.
     * To use a different backend (Micrometer, OpenTelemetry, Prometheus…), implement
     * {@link MetricsCollector} and replace this method.
     */
    public static MetricsCollector buildMetricsCollector() {
        boolean enabled = !"false".equalsIgnoreCase(env("METRICS_ENABLED", "true"));
        if (!enabled) {
            EtherLog.info(ApplicationBootstrap.class, "Métricas deshabilitadas (METRICS_ENABLED=false).");
            return MetricsCollector.noop();
        }
        EtherLog.info(ApplicationBootstrap.class,
                "Métricas habilitadas — backend=logging (structured log lines).");
        return new LoggingMetricsCollector();
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

    // Stub model client extracted to StubModelClient.java
}
