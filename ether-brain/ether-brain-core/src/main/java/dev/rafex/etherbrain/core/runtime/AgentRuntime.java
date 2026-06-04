package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.ports.memory.MemoryProvider;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.runtime.StepListener;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;

/**
 * Entry point for running a single agent turn.
 *
 * <p>Implements {@link AgentRunner} so it can be embedded as a tool inside
 * another agent via {@link AgentTool}, enabling in-process multi-agent
 * collaboration without HTTP overhead.
 *
 * <h2>Memory (hybrid)</h2>
 * If a {@link MemoryProvider} is configured:
 * <ul>
 *   <li><b>Automatic recall</b> — before each turn, relevant past context is
 *       retrieved and injected into the system prompt transparently.</li>
 *   <li><b>Automatic remember</b> — after each turn, the exchange is stored
 *       asynchronously in the memory provider (virtual thread, non-blocking).</li>
 *   <li><b>Manual commit</b> — the model can call {@code memory_commit} tool
 *       to promote important context to long-term storage.</li>
 * </ul>
 *
 * <h2>Cancellation</h2>
 * Pass a {@link CancellationToken.Mutable} to {@link #run(String, String, CancellationToken)}
 * and call {@code token.cancel()} from any thread. The loop stops at the next
 * step boundary and throws {@code AgentException("Agent loop cancelled")}.
 *
 * <h2>Multi-agent</h2>
 * To use this agent as a sub-agent of another:
 * <pre>{@code
 * AgentRuntime researcher = ...;
 * toolRegistry.register(new AgentTool(researcher));
 * enabledTools.add(researcher.agentName());
 * }</pre>
 */
public final class AgentRuntime implements AgentRunner {

    private static final System.Logger LOG = System.getLogger(AgentRuntime.class.getName());

    private final SessionStore     sessionStore;
    private final AgentLoop        agentLoop;
    private final AgentConfig      agentConfig;
    private final MemoryProvider   memoryProvider;   // null = sin memoria semántica
    private final String           name;
    private final String           description;
    private final MetricsCollector metrics;

    // ── Constructors ─────────────────────────────────────────────────────────

    /** Single-agent, without semantic memory (backward-compatible). */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig) {
        this(sessionStore, agentLoop, agentConfig, null, "agent", "AI agent",
                MetricsCollector.noop());
    }

    /** Single-agent, with semantic memory. */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig, MemoryProvider memoryProvider) {
        this(sessionStore, agentLoop, agentConfig, memoryProvider, "agent", "AI agent",
                MetricsCollector.noop());
    }

    /**
     * With name and description (used when this runtime will be embedded as a
     * sub-agent inside another via {@link AgentTool}).
     *
     * @param name        unique agent name (used as tool name by AgentTool)
     * @param description description shown to the orchestrating model
     */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig, MemoryProvider memoryProvider,
                        String name, String description) {
        this(sessionStore, agentLoop, agentConfig, memoryProvider, name, description,
                MetricsCollector.noop());
    }

    /**
     * Full constructor — with metrics collector.
     *
     * @param metrics collector for agent-level metrics (run count, duration); use
     *                {@link MetricsCollector#noop()} to disable
     */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig, MemoryProvider memoryProvider,
                        String name, String description, MetricsCollector metrics) {
        this.sessionStore   = sessionStore;
        this.agentLoop      = agentLoop;
        this.agentConfig    = agentConfig;
        this.memoryProvider = memoryProvider;
        this.name           = name;
        this.description    = description;
        this.metrics        = metrics != null ? metrics : MetricsCollector.noop();
    }

    // ── AgentRunner ──────────────────────────────────────────────────────────

    @Override
    public String agentName() { return name; }

    @Override
    public String agentDescription() { return description; }

    // ── run() overloads ───────────────────────────────────────────────────────

    /** Run without cancellation support (backward-compatible). */
    @Override
    public String run(String sessionId, String userMessage) throws Exception {
        return runInternal(sessionId, userMessage, null, null, null);
    }

    /**
     * Run a single turn with optional cancellation.
     *
     * @param sessionId         conversation session identifier
     * @param userMessage       the user's message
     * @param cancellationToken token that can stop the loop; {@code null} = no cancellation
     */
    @Override
    public String run(String sessionId, String userMessage,
                      CancellationToken cancellationToken) throws Exception {
        return runInternal(sessionId, userMessage, cancellationToken, null, null);
    }

    /**
     * Run with cancellation + real-time progress events via {@link StepListener}.
     * Used by the SSE endpoint to stream per-step events to the HTTP client.
     *
     * @param sessionId         conversation session identifier
     * @param userMessage       the user's message
     * @param cancellationToken token that can stop the loop; {@code null} = no cancellation
     * @param listener          receives step events; {@code null} = no events
     */
    public String run(String sessionId, String userMessage,
                      CancellationToken cancellationToken,
                      StepListener listener) throws Exception {
        return runInternal(sessionId, userMessage, cancellationToken, listener, null);
    }

    /**
     * Run with cancellation + step listener + HTTP correlation ID.
     *
     * <p>Used by the HTTP transport so that all metrics and log lines for this
     * turn carry the same {@code requestId} that was returned to the caller in
     * the {@code X-Request-ID} response header.
     *
     * @param requestId short correlation ID generated by the HTTP layer; may be {@code null}
     */
    public String run(String sessionId, String userMessage,
                      CancellationToken cancellationToken,
                      StepListener listener,
                      String requestId) throws Exception {
        return runInternal(sessionId, userMessage, cancellationToken, listener, requestId);
    }

    // ── Core implementation ───────────────────────────────────────────────────

    private String runInternal(String sessionId, String userMessage,
                                CancellationToken cancellationToken,
                                StepListener listener,
                                String requestId) throws Exception {

        ConversationState state = sessionStore.load(sessionId);
        state.add(new Message(Message.Role.USER, userMessage));

        // ── Recall: contexto relevante de memoria (no-fatal si falla) ────────
        String memCtx = null;
        if (memoryProvider != null) {
            try {
                memCtx = memoryProvider.recall(sessionId, userMessage);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Memory recall failed for session {0} (non-fatal): {1}",
                        sessionId, e.getMessage());
            }
        }

        // ── Loop del agente ───────────────────────────────────────────────────
        ExecutionContext ctx = new ExecutionContext(
                sessionId, state, agentConfig, memCtx, cancellationToken, requestId);
        // Inject per-request step listener if provided (creates a lightweight copy of the loop)
        AgentLoop loop = (listener != null) ? agentLoop.withListener(listener) : agentLoop;
        String finalAnswer = loop.run(ctx);

        sessionStore.save(sessionId, state);

        // ── Remember: guardar el turno en memoria (async, no-blocking) ────────
        if (memoryProvider != null) {
            final String turn    = userMessage;
            final String answer  = finalAnswer;
            final String session = sessionId;
            Thread.startVirtualThread(() -> {
                try {
                    memoryProvider.remember(session, turn, answer);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Memory remember failed for session {0} (non-fatal): {1}",
                            session, e.getMessage());
                }
            });
        }

        return finalAnswer;
    }
}
