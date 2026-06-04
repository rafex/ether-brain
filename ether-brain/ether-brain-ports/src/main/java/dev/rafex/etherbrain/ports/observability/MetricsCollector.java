package dev.rafex.etherbrain.ports.observability;

import java.time.Duration;

/**
 * Agnostic metrics collection port.
 *
 * <p>The interface is intentionally minimal so the core runtime stays
 * decoupled from any vendor library. Swap implementations without
 * touching business logic:
 *
 * <pre>
 * Backend             Implementation class
 * ─────────────────────────────────────────────────────────────────
 * Structured logs     LoggingMetricsCollector  (default, built-in)
 * Micrometer          MicrometerMetricsCollector (separate adapter)
 * OpenTelemetry       OtelMetricsCollector       (separate adapter)
 * Prometheus (direct) PrometheusMetricsCollector (separate adapter)
 * Disabled / tests    MetricsCollector.noop()
 * </pre>
 *
 * <h2>Tags</h2>
 * Tags are {@code "key=value"} strings — compatible with Prometheus labels,
 * DataDog tags, and structured log parsers:
 * <pre>
 * metrics.increment("http.requests.total", "endpoint=run", "status=200");
 * metrics.record("agent.run.duration", Duration.ofMillis(340), "status=ok");
 * metrics.gauge("event.queue.size", 5);
 * </pre>
 *
 * <h2>Naming convention</h2>
 * Use lowercase dot-separated names, consistent with Prometheus/Micrometer:
 * {@code http.requests.total}, {@code tool.execution.duration}, etc.
 */
public interface MetricsCollector {

    /**
     * Increments a counter by 1.
     *
     * @param name dot-separated metric name (e.g. {@code "tool.executions.total"})
     * @param tags zero or more {@code "key=value"} pairs
     */
    void increment(String name, String... tags);

    /**
     * Records a duration measurement (maps to histogram/timer in time-series backends).
     *
     * @param name     metric name
     * @param duration elapsed time
     * @param tags     zero or more {@code "key=value"} pairs
     */
    void record(String name, Duration duration, String... tags);

    /**
     * Sets a gauge to an instantaneous absolute value.
     *
     * @param name  metric name
     * @param value current value
     * @param tags  zero or more {@code "key=value"} pairs
     */
    void gauge(String name, long value, String... tags);

    // ── Built-in implementations ──────────────────────────────────────────────

    /** Returns a no-op implementation that discards all measurements. Use in tests. */
    static MetricsCollector noop() {
        return NoopMetricsCollector.INSTANCE;
    }

    /** No-op implementation — all methods are empty. */
    final class NoopMetricsCollector implements MetricsCollector {
        private static final NoopMetricsCollector INSTANCE = new NoopMetricsCollector();
        private NoopMetricsCollector() {}

        @Override public void increment(String name, String... tags) {}
        @Override public void record(String name, Duration duration, String... tags) {}
        @Override public void gauge(String name, long value, String... tags) {}
    }
}
