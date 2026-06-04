package dev.rafex.etherbrain.core.observability;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@link MetricsCollector} that emits each measurement as a structured log line
 * via {@link EtherLog}.
 *
 * <h2>Output format</h2>
 * <pre>
 * [METRIC] counter http.requests.total 1 endpoint=run status=200 requestId=a1b2c3
 * [METRIC] timer   agent.run.duration  1234ms status=ok sessionId=sess-1
 * [METRIC] gauge   event.queue.size    5
 * </pre>
 *
 * <p>Each line is parseable by standard log processors without extra agents:
 * <ul>
 *   <li><b>ELK</b> — Logstash grok / dissect filter on {@code [METRIC]}</li>
 *   <li><b>Loki + Grafana</b> — LogQL metric queries on log lines</li>
 *   <li><b>CloudWatch Logs Insights</b> — filter on "[METRIC]" + parse fields</li>
 *   <li><b>Fluent Bit</b> — record_modifier + lua filter</li>
 * </ul>
 *
 * <h2>Upgrading to a dedicated backend</h2>
 * Implement {@link MetricsCollector} against Micrometer, OpenTelemetry, or
 * Prometheus client and swap the instance in {@code ApplicationBootstrap}.
 * No other code changes required.
 */
public final class LoggingMetricsCollector implements MetricsCollector {

    private static final String METRIC = "[METRIC] ";

    @Override
    public void increment(String name, String... tags) {
        EtherLog.info(LoggingMetricsCollector.class,
                METRIC + "counter {} 1{}", name, tagSuffix(tags));
    }

    @Override
    public void record(String name, Duration duration, String... tags) {
        EtherLog.info(LoggingMetricsCollector.class,
                METRIC + "timer {} {}ms{}", name, duration.toMillis(), tagSuffix(tags));
    }

    @Override
    public void gauge(String name, long value, String... tags) {
        EtherLog.info(LoggingMetricsCollector.class,
                METRIC + "gauge {} {}{}", name, value, tagSuffix(tags));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a space-prefixed, space-separated tag string, or an empty string
     * when no tags are provided.
     *
     * <p>Example: {@code " endpoint=run status=200 requestId=a1b2c3"}
     */
    private static String tagSuffix(String[] tags) {
        if (tags == null || tags.length == 0) return "";
        String joined = Arrays.stream(tags)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isBlank() ? "" : " " + joined;
    }
}
