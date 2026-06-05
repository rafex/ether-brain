package dev.rafex.etherbrain.mqtt;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Puente entre el broker MQTT y el runtime del agente.
 *
 * <h2>Flujo por mensaje</h2>
 * <pre>
 * Broker MQTT
 *   └─► messageArrived()  [hilo Paho]
 *         └─► virtual thread
 *               ├─ parse JSON  →  MqttEventMessage
 *               ├─ agentRunner.run(sessionId, message)
 *               └─ publish response  →  reply_to topic
 * </pre>
 *
 * <h2>Threading</h2>
 * <p>Paho entrega mensajes en un único hilo de callback. Para no bloquearlo,
 * cada mensaje se despacha a un {@link ExecutorService} basado en virtual threads
 * (Java 21). Esto permite concurrencia ilimitada sin consumir platform threads.
 *
 * <h2>Reconexión</h2>
 * <p>Se activa {@code setAutomaticReconnect(true)} en {@link MqttConnectOptions}.
 * Paho reintenta con backoff exponencial y re-suscribe automáticamente cuando
 * {@code cleanSession=false}.
 */
public class MqttAgentBridge {

    private final MqttConfig        config;
    private final AgentRunner       agentRunner;
    private final MetricsCollector  metrics;

    private MqttClient              client;
    private ExecutorService         executor;
    private final AtomicBoolean     running = new AtomicBoolean(false);

    public MqttAgentBridge(MqttConfig config,
                           AgentRunner agentRunner,
                           MetricsCollector metrics) {
        this.config      = config;
        this.agentRunner = agentRunner;
        this.metrics     = metrics;
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Conecta al broker y empieza a escuchar mensajes.
     *
     * @throws MqttException si la conexión inicial falla
     */
    public void start() throws MqttException {
        executor = Executors.newVirtualThreadPerTaskExecutor();

        client = new MqttClient(config.brokerUrl(), config.clientId(),
                new MemoryPersistence());

        MqttConnectOptions opts = buildConnectOptions();

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                EtherLog.warn(MqttAgentBridge.class,
                        "MQTT: conexión perdida — reconectando… ({})", cause.getMessage());
                metrics.increment("mqtt.connection.lost");
            }

            @Override
            public void messageArrived(String topic, MqttMessage msg) {
                // Paho usa un único hilo de callback — despachar rápido a virtual thread
                executor.submit(() -> handleMessage(topic, msg));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no-op: QoS 0/1 no requiere acción al completar
            }
        });

        client.connect(opts);
        running.set(true);

        // Suscribirse al topic de entrada (puede incluir wildcards MQTT: + y #)
        client.subscribe(config.requestTopic(), config.qos());

        EtherLog.info(MqttAgentBridge.class,
                "MQTT bridge activo — broker={} clientId={} topic={} QoS={}",
                config.brokerUrl(), config.clientId(),
                config.requestTopic(), config.qos());

        metrics.increment("mqtt.bridge.started");
    }

    /** Desconecta del broker y libera el executor de virtual threads. */
    public void stop() {
        running.set(false);
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                EtherLog.info(MqttAgentBridge.class, "MQTT bridge detenido.");
            }
        } catch (MqttException e) {
            EtherLog.warn(MqttAgentBridge.class,
                    "Error al desconectar MQTT: {}", e.getMessage());
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    /** {@code true} si el bridge está conectado y procesando mensajes. */
    public boolean isRunning() {
        return running.get() && client != null && client.isConnected();
    }

    // ── Procesamiento de mensajes ─────────────────────────────────────────────

    /**
     * Procesa un mensaje MQTT entrante en un virtual thread.
     * Parsea el JSON, invoca al agente y publica la respuesta.
     */
    void handleMessage(String topic, MqttMessage mqttMessage) {
        String payload = new String(mqttMessage.getPayload(), StandardCharsets.UTF_8);
        MqttEventMessage event = MqttEventMessage.parse(payload, config.responseTopic());

        if (event == null) {
            EtherLog.warn(MqttAgentBridge.class,
                    "MQTT: mensaje ignorado en topic '{}' — JSON inválido o sin campo 'message'",
                    topic);
            metrics.increment("mqtt.messages.invalid");
            return;
        }

        EtherLog.info(MqttAgentBridge.class,
                "MQTT: mensaje recibido session={} reply_to={}",
                event.sessionId(), event.replyTo());
        metrics.increment("mqtt.messages.received", "topic=" + topic);

        Instant start = Instant.now();
        try {
            String answer = agentRunner.run(event.sessionId(), event.message());
            Duration elapsed = Duration.between(start, Instant.now());

            publish(event.replyTo(), event.toResponseJson(answer));
            metrics.increment("mqtt.messages.published", "topic=" + event.replyTo());
            metrics.increment("mqtt.messages.processed", "status=ok");
            metrics.record("mqtt.agent.duration", elapsed, "session=" + event.sessionId());

            EtherLog.info(MqttAgentBridge.class,
                    "MQTT: respuesta publicada en '{}' ({}ms)",
                    event.replyTo(), elapsed.toMillis());

        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());
            EtherLog.error(MqttAgentBridge.class,
                    "MQTT: error procesando session={}: {}", event.sessionId(), e.getMessage());
            metrics.increment("mqtt.messages.processed", "status=error");
            metrics.record("mqtt.agent.duration", elapsed, "session=" + event.sessionId());

            tryPublishError(event, e.getMessage());
        }
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    /**
     * Publica un payload en un topic MQTT.
     * Package-private para que los tests puedan sobreescribir este método
     * sin necesitar un broker real.
     */
    void publish(String topic, String responseJson) throws MqttException {
        if (client == null || !client.isConnected()) {
            EtherLog.warn(MqttAgentBridge.class,
                    "MQTT: no conectado, no se puede publicar en '{}'", topic);
            return;
        }
        MqttMessage response = new MqttMessage(
                responseJson.getBytes(StandardCharsets.UTF_8));
        response.setQos(config.qos());
        response.setRetained(false);
        client.publish(topic, response);
    }

    private void tryPublishError(MqttEventMessage event, String errorMsg) {
        try {
            publish(event.replyTo(), event.toErrorJson(errorMsg));
        } catch (Exception ex) {
            EtherLog.warn(MqttAgentBridge.class,
                    "MQTT: no se pudo publicar error en '{}': {}",
                    event.replyTo(), ex.getMessage());
        }
    }

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(config.cleanSession());
        opts.setKeepAliveInterval(config.keepAliveSecs());
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(10);

        if (config.username() != null && !config.username().isBlank()) {
            opts.setUserName(config.username());
            if (config.password() != null) {
                opts.setPassword(config.password().toCharArray());
            }
        }
        return opts;
    }
}
