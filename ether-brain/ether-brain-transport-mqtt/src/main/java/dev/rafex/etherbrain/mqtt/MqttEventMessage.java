package dev.rafex.etherbrain.mqtt;

import dev.rafex.etherbrain.common.JsonUtils;

/**
 * Mensaje MQTT entrante ya parseado.
 *
 * <h2>Formato JSON esperado en el topic de entrada</h2>
 * <pre>{@code
 * {
 *   "session_id": "mi-sesion",          ← obligatorio
 *   "message":    "¿Qué hora es?",      ← obligatorio
 *   "reply_to":   "etherbrain/res/s1"   ← opcional: topic de respuesta personalizado
 * }
 * }</pre>
 *
 * <p>Si {@code session_id} está ausente se genera uno con el prefijo {@code mqtt-}
 * seguido del hash del payload para mantener idempotencia.
 *
 * <p>Si {@code message} está ausente el mensaje es inválido y se ignora
 * (el bridge lo loguea como warn).
 */
public record MqttEventMessage(
        String sessionId,
        String message,
        String replyTo      // null si no está en el payload
) {

    /**
     * Parsea el payload JSON de un mensaje MQTT.
     *
     * @param json  contenido del mensaje como String (UTF-8)
     * @param defaultResponseTopic  topic base de respuesta ({@code MQTT_RESPONSE_TOPIC});
     *                              usado para construir {@code replyTo} cuando el payload
     *                              no incluye el campo
     * @return mensaje parseado, o {@code null} si el JSON no contiene {@code message}
     */
    public static MqttEventMessage parse(String json, String defaultResponseTopic) {
        if (json == null || json.isBlank()) return null;

        String message = JsonUtils.extractField(json, "message");
        if (message == null || message.isBlank()) return null;

        // session_id: usa el del payload o genera uno derivado del hash
        String sessionId = JsonUtils.extractField(json, "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "mqtt-" + Integer.toHexString(json.hashCode() & 0x7fffffff);
        }

        // reply_to: usa el del payload o construye a partir del topic base
        String replyTo = JsonUtils.extractField(json, "reply_to");
        if (replyTo == null || replyTo.isBlank()) {
            // defaultResponseTopic/session_id, ej.: etherbrain/responses/mi-sesion
            replyTo = defaultResponseTopic.replaceAll("/+$", "") + "/" + sessionId;
        }

        return new MqttEventMessage(sessionId, message, replyTo);
    }

    // ── Serialización de respuestas ───────────────────────────────────────────

    /**
     * Construye el payload JSON de una respuesta exitosa.
     *
     * <pre>{@code
     * {"session_id":"s1","answer":"Son las 10:30","status":"ok"}
     * }</pre>
     */
    public String toResponseJson(String answer) {
        return "{\"session_id\":" + JsonUtils.toJsonString(sessionId) +
               ",\"answer\":"     + JsonUtils.toJsonString(answer)    +
               ",\"status\":\"ok\"}";
    }

    /**
     * Construye el payload JSON de una respuesta de error.
     *
     * <pre>{@code
     * {"session_id":"s1","error":"timeout","status":"error"}
     * }</pre>
     */
    public String toErrorJson(String error) {
        return "{\"session_id\":" + JsonUtils.toJsonString(sessionId) +
               ",\"error\":"      + JsonUtils.toJsonString(error)     +
               ",\"status\":\"error\"}";
    }
}
