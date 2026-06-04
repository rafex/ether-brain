package dev.rafex.etherbrain.ports.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Token that signals an {@link dev.rafex.etherbrain.ports.runtime.AgentRunner}
 * that it should stop processing.
 *
 * <p>The token is checked at the start of each step inside the agent loop.
 * When cancelled, the loop throws {@link dev.rafex.etherbrain.common.AgentException}
 * with message {@code "Agent loop cancelled"}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * CancellationToken.Mutable token = CancellationToken.create();
 *
 * // In another thread / HTTP handler:
 * token.cancel();
 *
 * // Pass to AgentRuntime — it will stop at the next step boundary
 * runtime.run(sessionId, message, token);
 * }</pre>
 *
 * <p>Thread-safe: {@link Mutable#cancel()} and {@link #isCancelled()} may be
 * called concurrently from different threads.
 */
public interface CancellationToken {

    /** Returns {@code true} if cancellation has been requested. */
    boolean isCancelled();

    /** A token that is never cancelled. */
    static CancellationToken noop() { return () -> false; }

    /** Creates a mutable token that can be cancelled from outside. */
    static Mutable create() { return new Mutable(); }

    /**
     * Mutable, thread-safe implementation. Obtain via {@link CancellationToken#create()}.
     *
     * <p>Once cancelled a token cannot be "un-cancelled".
     */
    final class Mutable implements CancellationToken {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** Request cancellation. Idempotent — calling multiple times is safe. */
        public void cancel() { cancelled.set(true); }

        @Override
        public boolean isCancelled() { return cancelled.get(); }
    }
}
