package dev.rafex.etherbrain.mqtt;

import java.util.UUID;

/**
 * Configuración del adaptador MQTT leída desde variables de entorno.
 *
 * <h2>Variables de entorno</h2>
 * <pre>
 * MQTT_BROKER          — URL del broker, ej. tcp://localhost:1883  (obligatoria)
 * MQTT_CLIENT_ID       — ID del cliente (default: ether-brain-{random})
 * MQTT_USERNAME        — usuario para autenticación (opcional)
 * MQTT_PASSWORD        — contraseña para autenticación (opcional)
 * MQTT_REQUEST_TOPIC   — topic de entrada (default: etherbrain/requests)
 * MQTT_RESPONSE_TOPIC  — topic base para respuestas (default: etherbrain/responses)
 * MQTT_QOS             — nivel de QoS 0|1|2 (default: 1)
 * MQTT_KEEPALIVE_SECS  — keepalive en segundos (default: 60)
 * MQTT_CLEAN_SESSION   — true|false (default: true)
 * </pre>
 *
 * <h2>Formato del mensaje entrante (JSON)</h2>
 * <pre>{@code
 * {
 *   "session_id": "mi-sesion",
 *   "message":    "¿Qué hora es?",
 *   "reply_to":   "etherbrain/responses/mi-sesion"   ← opcional, sobreescribe el default
 * }
 * }</pre>
 *
 * <h2>Topic de respuesta</h2>
 * <p>Si el mensaje incluye {@code reply_to} se publica ahí.
 * Si no, la respuesta se publica en {@code {MQTT_RESPONSE_TOPIC}/{session_id}}.
 *
 * <h2>Ejemplo con Mosquitto</h2>
 * <pre>
 * # Suscribirse a las respuestas antes de enviar:
 * mosquitto_sub -h localhost -t "etherbrain/responses/#" -v
 *
 * # Enviar un mensaje al agente:
 * mosquitto_pub -h localhost \
 *   -t "etherbrain/requests" \
 *   -m '{"session_id":"s1","message":"¿Cuál es la fecha de hoy?"}'
 * </pre>
 */
public record MqttConfig(
        String  brokerUrl,
        String  clientId,
        String  username,
        String  password,
        String  requestTopic,
        String  responseTopic,
        int     qos,
        int     keepAliveSecs,
        boolean cleanSession
) {

    /**
     * Construye la configuración desde variables de entorno / system properties.
     *
     * @throws IllegalStateException si {@code MQTT_BROKER} no está definida
     */
    public static MqttConfig fromEnv() {
        String broker = env("MQTT_BROKER", null);
        if (broker == null || broker.isBlank()) {
            throw new IllegalStateException(
                    "MQTT_BROKER no definida. Ejemplo: MQTT_BROKER=tcp://localhost:1883");
        }

        String clientId = env("MQTT_CLIENT_ID",
                "ether-brain-" + UUID.randomUUID().toString().substring(0, 8));

        String requestTopic  = env("MQTT_REQUEST_TOPIC",  "etherbrain/requests");
        String responseTopic = env("MQTT_RESPONSE_TOPIC", "etherbrain/responses");

        int qos          = parseInt(env("MQTT_QOS",            "1"), 1);
        int keepAlive    = parseInt(env("MQTT_KEEPALIVE_SECS", "60"), 60);
        boolean clean    = Boolean.parseBoolean(env("MQTT_CLEAN_SESSION", "true"));

        String username  = env("MQTT_USERNAME", null);
        String password  = env("MQTT_PASSWORD", null);

        return new MqttConfig(broker, clientId, username, password,
                requestTopic, responseTopic, qos, keepAlive, clean);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
