package dev.rafex.etherbrain.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests de la lógica de {@link MqttAgentBridge#handleMessage} sin broker real.
 *
 * <p>Usa una subclase {@link RecordingBridge} que sobreescribe {@code publish()}
 * para capturar topic + payload en lugar de enviarlos a Paho. De este modo los
 * tests no necesitan ningún broker Mosquitto.
 */
class MqttAgentBridgeTest {

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private static final AgentRunner ECHO_RUNNER = new AgentRunner() {
        @Override public String agentName()        { return "echo"; }
        @Override public String agentDescription() { return "Eco de prueba"; }
        @Override public String run(String sessionId, String message) {
            return "echo: " + message;
        }
    };

    private static final AgentRunner FAILING_RUNNER = new AgentRunner() {
        @Override public String agentName()        { return "failing"; }
        @Override public String agentDescription() { return "Siempre falla"; }
        @Override public String run(String sessionId, String message) throws Exception {
            throw new RuntimeException("error simulado");
        }
    };

    /** Captura métricas emitidas durante el procesamiento. */
    private static final class CapturingMetrics implements MetricsCollector {
        final List<String> increments = new ArrayList<>();
        @Override public void increment(String name, String... tags) { increments.add(name); }
        @Override public void record(String name, Duration duration, String... tags) {}
        @Override public void gauge(String name, long value, String... tags) {}
    }

    /**
     * Subclase de bridge que intercepta publish() para capturar lo publicado
     * sin necesitar un MqttClient real conectado a un broker.
     */
    private static final class RecordingBridge extends MqttAgentBridge {
        record Published(String topic, String payload) {}
        final List<Published> published = new ArrayList<>();

        RecordingBridge(MqttConfig cfg, AgentRunner runner, MetricsCollector metrics) {
            super(cfg, runner, metrics);
        }

        @Override
        void publish(String topic, String payload) throws MqttException {
            published.add(new Published(topic, payload));
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private MqttConfig       config;
    private CapturingMetrics metrics;

    @BeforeEach
    void setup() {
        System.setProperty("MQTT_BROKER", "tcp://localhost:1883");
        config  = MqttConfig.fromEnv();
        metrics = new CapturingMetrics();
        System.clearProperty("MQTT_BROKER");
    }

    // ── Mensaje válido → respuesta ok ─────────────────────────────────────────

    @Test
    void validMessageDispatchesToAgentAndPublishesResponse() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"session_id\":\"s1\",\"message\":\"Hola agente\"}"));

        assertFalse(bridge.published.isEmpty(), "Debe publicarse una respuesta");
        RecordingBridge.Published pub = bridge.published.get(0);
        assertEquals("etherbrain/responses/s1", pub.topic());
        assertTrue(pub.payload().contains("echo: Hola agente"), "Debe incluir el eco");
        assertTrue(pub.payload().contains("\"status\":\"ok\""),  "Status debe ser ok");
    }

    @Test
    void customReplyToTopicIsRespected() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests", mqttMsg(
                "{\"session_id\":\"s2\",\"message\":\"test\","
                + "\"reply_to\":\"custom/topic/out\"}"));

        assertFalse(bridge.published.isEmpty());
        assertEquals("custom/topic/out", bridge.published.get(0).topic(),
                "Debe usar el topic reply_to del mensaje");
    }

    @Test
    void sessionIdIsGeneratedWhenAbsent() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"message\":\"Sin session_id\"}"));

        assertFalse(bridge.published.isEmpty());
        // El topic de respuesta debe empezar con etherbrain/responses/mqtt-
        String topic = bridge.published.get(0).topic();
        assertTrue(topic.startsWith("etherbrain/responses/mqtt-"),
                "Topic debe contener sessionId autogenerado, fue: " + topic);
    }

    // ── Error del agente → respuesta error ───────────────────────────────────

    @Test
    void agentExceptionPublishesErrorResponse() {
        RecordingBridge bridge = new RecordingBridge(config, FAILING_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"session_id\":\"s3\",\"message\":\"Falla\"}"));

        assertFalse(bridge.published.isEmpty(), "Debe publicarse un error");
        String payload = bridge.published.get(0).payload();
        assertTrue(payload.contains("\"status\":\"error\""), "Status debe ser error");
        assertTrue(payload.contains("error simulado"),        "Debe incluir el mensaje de error");
    }

    // ── Mensajes inválidos → ignorados ────────────────────────────────────────

    @Test
    void missingMessageFieldIsIgnored() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"session_id\":\"s4\",\"other\":\"value\"}"));

        assertTrue(bridge.published.isEmpty(), "No debe publicarse nada");
        assertTrue(metrics.increments.contains("mqtt.messages.invalid"),
                "Debe registrar métrica de mensaje inválido");
    }

    @Test
    void emptyPayloadIsIgnored() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests", mqttMsg(""));

        assertTrue(bridge.published.isEmpty(), "No debe publicarse nada");
    }

    // ── Métricas ──────────────────────────────────────────────────────────────

    @Test
    void receivedAndProcessedMetricsAreEmittedOnSuccess() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"message\":\"ping\"}"));

        assertTrue(metrics.increments.contains("mqtt.messages.received"),
                "Debe emitir mqtt.messages.received");
        assertTrue(metrics.increments.contains("mqtt.messages.processed"),
                "Debe emitir mqtt.messages.processed");
    }

    @Test
    void publishedMetricIsEmittedOnSuccess() {
        RecordingBridge bridge = new RecordingBridge(config, ECHO_RUNNER, metrics);

        bridge.handleMessage("etherbrain/requests",
                mqttMsg("{\"session_id\":\"s5\",\"message\":\"ok\"}"));

        assertTrue(metrics.increments.contains("mqtt.messages.published"),
                "Debe emitir mqtt.messages.published");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static MqttMessage mqttMsg(String json) {
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }
}
