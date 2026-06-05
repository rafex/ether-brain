package dev.rafex.etherbrain.core.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoggingMetricsCollectorTest {

    private LoggingMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new LoggingMetricsCollector();
    }

    // ── LoggingMetricsCollector ───────────────────────────────────────────────

    @Test
    void incrementDoesNotThrow() {
        assertDoesNotThrow(() -> collector.increment("http.requests.total"));
    }

    @Test
    void incrementWithTagsDoesNotThrow() {
        assertDoesNotThrow(() ->
                collector.increment("http.requests.total", "endpoint=run", "status=200"));
    }

    @Test
    void recordDoesNotThrow() {
        assertDoesNotThrow(() ->
                collector.record("agent.run.duration", Duration.ofMillis(123)));
    }

    @Test
    void recordWithTagsDoesNotThrow() {
        assertDoesNotThrow(() ->
                collector.record("tool.execution.duration", Duration.ofMillis(42),
                        "tool=echo", "status=ok"));
    }

    @Test
    void gaugeDoesNotThrow() {
        assertDoesNotThrow(() -> collector.gauge("event.queue.size", 5));
    }

    @Test
    void gaugeWithTagsDoesNotThrow() {
        assertDoesNotThrow(() ->
                collector.gauge("event.queue.size", 5, "region=us-east-1"));
    }

    // ── MetricsCollector.noop ─────────────────────────────────────────────────

    @Test
    void noopIncrementDoesNotThrow() {
        MetricsCollector noop = MetricsCollector.noop();
        assertDoesNotThrow(() -> noop.increment("any.counter", "k=v"));
    }

    @Test
    void noopRecordDoesNotThrow() {
        MetricsCollector noop = MetricsCollector.noop();
        assertDoesNotThrow(() -> noop.record("any.timer", Duration.ofMillis(1)));
    }

    @Test
    void noopGaugeDoesNotThrow() {
        MetricsCollector noop = MetricsCollector.noop();
        assertDoesNotThrow(() -> noop.gauge("any.gauge", 99));
    }

    @Test
    void noopWithNullTagsDoesNotThrow() {
        MetricsCollector noop = MetricsCollector.noop();
        assertDoesNotThrow(() -> noop.increment("x", (String[]) null));
    }

    @Test
    void loggingWithNullTagsDoesNotThrow() {
        assertDoesNotThrow(() -> collector.increment("x", (String[]) null));
    }

    @Test
    void loggingWithEmptyTagsDoesNotThrow() {
        assertDoesNotThrow(() -> collector.increment("x"));
    }
}
