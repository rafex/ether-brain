package dev.rafex.etherbrain.core.policy;

import dev.rafex.etherbrain.ports.policy.RetryPolicy;

/**
 * Default retry policy implementation: fixed number of retries with
 * configurable delay.
 *
 * <p>Configured at startup via environment variables:
 * <pre>
 * AGENT_RETRY_MAX      — max retries per tool (default: 0, i.e. no retry)
 * AGENT_RETRY_DELAY_MS — delay between retries in ms (default: 500)
 * </pre>
 *
 * <p>Example — 3 retries with 1 second between each:
 * <pre>
 * AGENT_RETRY_MAX=3
 * AGENT_RETRY_DELAY_MS=1000
 * </pre>
 */
public final class DefaultRetryPolicy implements RetryPolicy {

    public static final int  DEFAULT_MAX_RETRIES  = 0;
    public static final long DEFAULT_DELAY_MILLIS = 500L;

    private final int  maxRetries;
    private final long delayMillis;

    public DefaultRetryPolicy(int maxRetries, long delayMillis) {
        this.maxRetries  = Math.max(0, maxRetries);
        this.delayMillis = Math.max(0, delayMillis);
    }

    /** No-retry convenience constructor. */
    public DefaultRetryPolicy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_DELAY_MILLIS);
    }

    @Override
    public boolean shouldRetry(String toolName, int attempt, Exception error) {
        return attempt < maxRetries;
    }

    @Override
    public long retryDelayMillis(int attempt) {
        return delayMillis;
    }

    public int maxRetries()  { return maxRetries; }
    public long delayMillis() { return delayMillis; }
}
