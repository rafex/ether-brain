package dev.rafex.etherbrain.mqtt;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;

/**
 * Punto de entrada del transporte MQTT de EtherBrain.
 *
 * <h2>Uso</h2>
 * <pre>
 * java -jar ether-brain-mqtt.jar
 * </pre>
 *
 * <h2>Variables mínimas</h2>
 * <pre>
 * MQTT_BROKER=tcp://localhost:1883   ← broker Mosquitto
 * LLM_URL=https://api.openai.com
 * LLM_TOKEN=sk-...
 * LLM_MODEL=gpt-4o
 * </pre>
 *
 * <h2>Prueba rápida con Mosquitto</h2>
 * <pre>
 * # Terminal 1 — suscribirse a las respuestas:
 * mosquitto_sub -h localhost -t "etherbrain/responses/#" -v
 *
 * # Terminal 2 — enviar un mensaje:
 * mosquitto_pub -h localhost \
 *   -t "etherbrain/requests" \
 *   -m '{"session_id":"s1","message":"¿Cuál es la fecha de hoy?"}'
 * </pre>
 *
 * <h2>Variables completas</h2>
 * <pre>
 * MQTT_BROKER           — URL del broker (obligatoria)
 * MQTT_CLIENT_ID        — ID del cliente (default: ether-brain-{random})
 * MQTT_USERNAME         — usuario (opcional)
 * MQTT_PASSWORD         — contraseña (opcional)
 * MQTT_REQUEST_TOPIC    — topic de entrada (default: etherbrain/requests)
 * MQTT_RESPONSE_TOPIC   — topic base de salida (default: etherbrain/responses)
 * MQTT_QOS              — 0|1|2 (default: 1)
 * MQTT_KEEPALIVE_SECS   — keepalive (default: 60)
 * MQTT_CLEAN_SESSION    — true|false (default: true)
 * + todas las variables LLM_*, AGENT_*, SESSION_*
 * </pre>
 */
public final class MqttMain {

    private MqttMain() {}

    public static void main(String[] args) throws Exception {
        // 1. Leer configuración MQTT y arrancar el runtime del agente
        MqttConfig config = MqttConfig.fromEnv();

        ApplicationBootstrap bootstrap = new ApplicationBootstrap();
        var agentRuntime = bootstrap.bootstrap();

        MetricsCollector metrics = ApplicationBootstrap.buildMetricsCollector();

        // 2. Crear y arrancar el bridge
        MqttAgentBridge bridge = new MqttAgentBridge(config, agentRuntime, metrics);
        bridge.start();

        // 3. Shutdown limpio con Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            EtherLog.info(MqttMain.class, "Apagando MQTT bridge…");
            bridge.stop();
        }));

        // 4. Mantener vivo el proceso (Paho usa sus propios hilos)
        Thread.currentThread().join();
    }
}
