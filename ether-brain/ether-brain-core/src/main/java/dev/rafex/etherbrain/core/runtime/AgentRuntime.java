package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.ports.memory.MemoryProvider;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;

/**
 * Entry point for running a single agent turn.
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
 */
public final class AgentRuntime {

    private static final System.Logger LOG = System.getLogger(AgentRuntime.class.getName());

    private final SessionStore     sessionStore;
    private final AgentLoop        agentLoop;
    private final AgentConfig      agentConfig;
    private final MemoryProvider   memoryProvider;   // null = sin memoria semántica

    /** Constructor sin memoria semántica (backward-compatible). */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig) {
        this(sessionStore, agentLoop, agentConfig, null);
    }

    /** Constructor con memoria semántica. */
    public AgentRuntime(SessionStore sessionStore, AgentLoop agentLoop,
                        AgentConfig agentConfig, MemoryProvider memoryProvider) {
        this.sessionStore   = sessionStore;
        this.agentLoop      = agentLoop;
        this.agentConfig    = agentConfig;
        this.memoryProvider = memoryProvider;
    }

    public String run(String sessionId, String userMessage) throws Exception {
        ConversationState state = sessionStore.load(sessionId);
        state.add(new Message(Message.Role.USER, userMessage));

        // ── Recall: contexto relevante de memoria (no-fatal si falla) ───────
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

        // ── Loop del agente ──────────────────────────────────────────────────
        ExecutionContext ctx = new ExecutionContext(sessionId, state, agentConfig, memCtx);
        String finalAnswer = agentLoop.run(ctx);

        sessionStore.save(sessionId, state);

        // ── Remember: guardar el turno en memoria (async, no-blocking) ───────
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
