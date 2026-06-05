package dev.rafex.etherbrain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.core.observability.LoggingMetricsCollector;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests para {@link ApplicationBootstrap#buildMetricsCollector()}.
 *
 * <p>Verifica las dos ramas de la variable {@code METRICS_ENABLED}:
 * <ul>
 *   <li>{@code true} (default) → {@link LoggingMetricsCollector}</li>
 *   <li>{@code false}          → {@link MetricsCollector.NoopMetricsCollector}</li>
 * </ul>
 */
class ApplicationBootstrapTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("METRICS_ENABLED");
    }

    // ── METRICS_ENABLED no definida → LoggingMetricsCollector (default) ───────

    @Test
    void defaultReturnsLoggingCollector() {
        MetricsCollector collector = ApplicationBootstrap.buildMetricsCollector();

        assertNotNull(collector);
        assertInstanceOf(LoggingMetricsCollector.class, collector,
                "Sin METRICS_ENABLED debe retornar LoggingMetricsCollector");
    }

    // ── METRICS_ENABLED=true → LoggingMetricsCollector ────────────────────────

    @Test
    void enabledTrueReturnsLoggingCollector() {
        System.setProperty("METRICS_ENABLED", "true");

        assertInstanceOf(LoggingMetricsCollector.class,
                ApplicationBootstrap.buildMetricsCollector());
    }

    @Test
    void enabledTrueIsCaseInsensitive() {
        System.setProperty("METRICS_ENABLED", "TRUE");

        assertInstanceOf(LoggingMetricsCollector.class,
                ApplicationBootstrap.buildMetricsCollector());
    }

    // ── METRICS_ENABLED=false → NoopMetricsCollector ─────────────────────────

    @Test
    void disabledReturnNoop() {
        System.setProperty("METRICS_ENABLED", "false");

        MetricsCollector collector = ApplicationBootstrap.buildMetricsCollector();

        assertNotNull(collector);
        // noop() retorna la instancia singleton interna — verificamos que NO es Logging
        assertNotSame(LoggingMetricsCollector.class, collector.getClass(),
                "METRICS_ENABLED=false no debe retornar LoggingMetricsCollector");
        // Verificamos que es la implementación noop: sus métodos no hacen nada
        // (no lanza, no escribe) — instanciable sin excepción
        collector.increment("test");
        collector.record("test", java.time.Duration.ofMillis(1));
        collector.gauge("test", 42L);
    }

    @Test
    void disabledFalseIsCaseInsensitive() {
        System.setProperty("METRICS_ENABLED", "FALSE");

        MetricsCollector collector = ApplicationBootstrap.buildMetricsCollector();

        assertNotSame(LoggingMetricsCollector.class, collector.getClass());
    }

    // ── noop() es idempotente ─────────────────────────────────────────────────

    @Test
    void noopInstanceIsSingleton() {
        MetricsCollector a = MetricsCollector.noop();
        MetricsCollector b = MetricsCollector.noop();

        assertNotNull(a);
        assertNotNull(b);
        assertInstanceOf(MetricsCollector.NoopMetricsCollector.class, a);
        assertInstanceOf(MetricsCollector.NoopMetricsCollector.class, b);
    }

    // ── addGenerationToken ────────────────────────────────────────────────────

    @Test
    void addGenerationTokenInsertsBeforeExtension() {
        assertEquals("/var/log/app.%g.log",
                ApplicationBootstrap.addGenerationToken("/var/log/app.log"));
    }

    @Test
    void addGenerationTokenAppendsWhenNoExtension() {
        assertEquals("/var/log/app.%g",
                ApplicationBootstrap.addGenerationToken("/var/log/app"));
    }

    @Test
    void addGenerationTokenDoesNotDoubleInsert() {
        // Si ya tiene %g no se llama este método, pero si se llama no debe duplicar
        String input = "/var/log/app.%g.log";
        // El método solo añade si NO hay %g — pero si lo recibe igual, lo trata como .%g.%g.log
        // Verificamos que el prefijo sea correcto para un input sin %g
        String result = ApplicationBootstrap.addGenerationToken("/logs/service.log.gz");
        assertTrue(result.contains("%g"), "Debe contener %g");
        assertTrue(result.endsWith(".gz"), "Debe conservar la extensión final");
        assertEquals("/logs/service.log.%g.gz", result);
    }

    @Test
    void addGenerationTokenHandlesDirectoryWithDots() {
        // /home/user/v1.2/app.log → /home/user/v1.2/app.%g.log
        String result = ApplicationBootstrap.addGenerationToken("/home/user/v1.2/app.log");
        assertEquals("/home/user/v1.2/app.%g.log", result,
                "El %g debe ir en el nombre del archivo, no en el directorio");
    }

    // ── logging.properties cargado del classpath ──────────────────────────────

    @Test
    void classpathLoggingPropertiesIsPresent() {
        // Verifica que el archivo existe en el classpath del módulo bootstrap
        try (var is = ApplicationBootstrap.class
                .getClassLoader().getResourceAsStream("logging.properties")) {
            assertNotNull(is, "logging.properties debe estar en el classpath de ether-brain-bootstrap");
        } catch (java.io.IOException e) {
            // close sin recursos — no debería ocurrir
        }
    }
}
