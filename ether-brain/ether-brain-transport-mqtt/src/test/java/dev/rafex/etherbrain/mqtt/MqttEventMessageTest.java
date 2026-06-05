package dev.rafex.etherbrain.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MqttEventMessageTest {

    private static final String RESPONSE_BASE = "etherbrain/responses";

    // ── parse — caso normal ───────────────────────────────────────────────────

    @Test
    void parsesFullMessage() {
        String json = "{\"session_id\":\"s1\",\"message\":\"¿Qué hora es?\","
                    + "\"reply_to\":\"custom/reply\"}";

        MqttEventMessage msg = MqttEventMessage.parse(json, RESPONSE_BASE);

        assertNotNull(msg);
        assertEquals("s1",           msg.sessionId());
        assertEquals("¿Qué hora es?", msg.message());
        assertEquals("custom/reply", msg.replyTo());
    }

    @Test
    void generatesReplyTopicWhenAbsent() {
        String json = "{\"session_id\":\"abc\",\"message\":\"Hola\"}";

        MqttEventMessage msg = MqttEventMessage.parse(json, RESPONSE_BASE);

        assertNotNull(msg);
        assertEquals("etherbrain/responses/abc", msg.replyTo());
    }

    @Test
    void generatesSessionIdWhenAbsent() {
        String json = "{\"message\":\"Sin sesión\"}";

        MqttEventMessage msg = MqttEventMessage.parse(json, RESPONSE_BASE);

        assertNotNull(msg);
        assertTrue(msg.sessionId().startsWith("mqtt-"),
                "sessionId autogenerado debe empezar con mqtt-");
        assertEquals("Sin sesión", msg.message());
    }

    // ── parse — casos inválidos ───────────────────────────────────────────────

    @Test
    void returnsNullWhenMessageFieldMissing() {
        String json = "{\"session_id\":\"s1\",\"other\":\"value\"}";
        assertNull(MqttEventMessage.parse(json, RESPONSE_BASE));
    }

    @Test
    void returnsNullForNullPayload() {
        assertNull(MqttEventMessage.parse(null, RESPONSE_BASE));
    }

    @Test
    void returnsNullForBlankPayload() {
        assertNull(MqttEventMessage.parse("   ", RESPONSE_BASE));
    }

    // ── toResponseJson ────────────────────────────────────────────────────────

    @Test
    void responseJsonContainsCorrectFields() {
        MqttEventMessage msg = new MqttEventMessage("s1", "pregunta", "t/out");
        String json = msg.toResponseJson("Son las 10:30");

        assertTrue(json.contains("\"session_id\""),     "debe tener session_id");
        assertTrue(json.contains("\"s1\""),             "debe incluir el valor de session_id");
        assertTrue(json.contains("\"answer\""),         "debe tener answer");
        assertTrue(json.contains("Son las 10:30"),      "debe incluir la respuesta");
        assertTrue(json.contains("\"status\":\"ok\""),  "status debe ser ok");
    }

    @Test
    void responseJsonEscapesSpecialCharacters() {
        MqttEventMessage msg = new MqttEventMessage("s1", "q", "t");
        String json = msg.toResponseJson("Dice \"hola\"\ny \"adiós\"");

        assertTrue(json.contains("\\\"hola\\\""), "Las comillas deben estar escapadas");
        assertTrue(json.contains("\\n"),           "El salto de línea debe estar escapado");
    }

    // ── toErrorJson ───────────────────────────────────────────────────────────

    @Test
    void errorJsonContainsCorrectFields() {
        MqttEventMessage msg = new MqttEventMessage("s2", "pregunta", "t/out");
        String json = msg.toErrorJson("timeout al invocar LLM");

        assertTrue(json.contains("\"session_id\""),        "debe tener session_id");
        assertTrue(json.contains("\"error\""),             "debe tener error");
        assertTrue(json.contains("timeout al invocar LLM"),"debe incluir el mensaje de error");
        assertTrue(json.contains("\"status\":\"error\""),  "status debe ser error");
    }

    // ── idempotencia de sessionId autogenerado ────────────────────────────────

    @Test
    void samePayloadProducesSameAutoSessionId() {
        String json = "{\"message\":\"Hola\"}";

        MqttEventMessage m1 = MqttEventMessage.parse(json, RESPONSE_BASE);
        MqttEventMessage m2 = MqttEventMessage.parse(json, RESPONSE_BASE);

        assertNotNull(m1);
        assertNotNull(m2);
        assertEquals(m1.sessionId(), m2.sessionId(),
                "El mismo payload debe producir el mismo sessionId autogenerado");
    }
}
