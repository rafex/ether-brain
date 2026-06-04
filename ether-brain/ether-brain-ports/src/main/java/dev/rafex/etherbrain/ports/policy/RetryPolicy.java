package dev.rafex.etherbrain.ports.policy;

/**
 * Policy that decides if a failed tool execution should be retried.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The agent loop calls {@link #shouldRetry} after each tool failure.</li>
 *   <li>If {@code true} is returned the loop re-executes the same tool call
 *       (without asking the model again) after waiting {@link #retryDelayMillis}.</li>
 *   <li>The conversation history records each failure as
 *       {@code "Error (retry N): ..."} so the model is aware if it ever
 *       receives a final failure.</li>
 * </ul>
 *
 * <h2>Implementing</h2>
 * <pre>{@code
 * RetryPolicy backoff = (name, attempt, err) -> attempt < 3;
 * // with 500ms exponential backoff:
 * RetryPolicy withDelay = new RetryPolicy() {
 *     public boolean shouldRetry(String n, int attempt, Exception e) { return attempt < 3; }
 *     public long retryDelayMillis(int attempt) { return 200L * (1L << attempt); }
 * };
 * }</pre>
 */
public interface RetryPolicy {

    /**
     * Whether the failed tool call should be retried.
     *
     * @param toolName  name of the tool that failed
     * @param attempt   zero-based attempt number (0 = first failure, 1 = second, …)
     * @param error     exception thrown by the tool
     * @return {@code true} to retry, {@code false} to propagate the error
     */
    boolean shouldRetry(String toolName, int attempt, Exception error);

    /**
     * How long to wait before the next retry (milliseconds).
     * Defaults to 0 (no delay). Implement for exponential / linear backoff.
     *
     * @param attempt zero-based attempt index (0 = delay before 1st retry)
     */
    default long retryDelayMillis(int attempt) { return 0L; }

    /** Policy that never retries. */
    static RetryPolicy none() { return (name, attempt, error) -> false; }

    /**
     * Policy that retries up to {@code maxAttempts} times with a fixed delay.
     *
     * @param maxAttempts maximum number of retries (not counting the original call)
     * @param delayMillis fixed delay between retries in milliseconds
     */
    static RetryPolicy fixed(int maxAttempts, long delayMillis) {
        return new RetryPolicy() {
            @Override public boolean shouldRetry(String n, int attempt, Exception e) {
                return attempt < maxAttempts;
            }
            @Override public long retryDelayMillis(int attempt) { return delayMillis; }
        };
    }

    /**
     * Policy that retries up to {@code maxAttempts} times with exponential backoff.
     *
     * @param maxAttempts  maximum number of retries
     * @param baseDelayMs  delay before first retry; doubles each subsequent attempt
     */
    static RetryPolicy exponentialBackoff(int maxAttempts, long baseDelayMs) {
        return new RetryPolicy() {
            @Override public boolean shouldRetry(String n, int attempt, Exception e) {
                return attempt < maxAttempts;
            }
            @Override public long retryDelayMillis(int attempt) {
                return baseDelayMs * (1L << attempt);
            }
        };
    }
}
