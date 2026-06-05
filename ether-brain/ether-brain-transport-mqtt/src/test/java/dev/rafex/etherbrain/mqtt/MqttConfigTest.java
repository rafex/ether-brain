package dev.rafex.etherbrain.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MqttConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("MQTT_BROKER");
        System.clearProperty("MQTT_CLIENT_ID");
        System.clearProperty("MQTT_USERNAME");
        System.clearProperty("MQTT_PASSWORD");
        System.clearProperty("MQTT_REQUEST_TOPIC");
        System.clearProperty("MQTT_RESPONSE_TOPIC");
        System.clearProperty("MQTT_QOS");
        System.clearProperty("MQTT_KEEPALIVE_SECS");
        System.clearProperty("MQTT_CLEAN_SESSION");
    }

    // ── fromEnv — fallo sin MQTT_BROKER ──────────────────────────────────────

    @Test
    void throwsWhenBrokerMissing() {
        assertThrows(IllegalStateException.class, MqttConfig::fromEnv);
    }

    // ── fromEnv — defaults ────────────────────────────────────────────────────

    @Test
    void defaultsAreApplied() {
        System.setProperty("MQTT_BROKER", "tcp://localhost:1883");

        MqttConfig cfg = MqttConfig.fromEnv();

        assertEquals("tcp://localhost:1883", cfg.brokerUrl());
        assertTrue(cfg.clientId().startsWith("ether-brain-"),
                "clientId debe empezar con ether-brain-");
        assertNull(cfg.username());
        assertNull(cfg.password());
        assertEquals("etherbrain/requests",  cfg.requestTopic());
        assertEquals("etherbrain/responses", cfg.responseTopic());
        assertEquals(1,    cfg.qos());
        assertEquals(60,   cfg.keepAliveSecs());
        assertTrue(cfg.cleanSession());
    }

    // ── fromEnv — valores personalizados ─────────────────────────────────────

    @Test
    void customValuesAreRead() {
        System.setProperty("MQTT_BROKER",          "ssl://broker.example.com:8883");
        System.setProperty("MQTT_CLIENT_ID",       "mi-agente-01");
        System.setProperty("MQTT_USERNAME",        "user");
        System.setProperty("MQTT_PASSWORD",        "pass");
        System.setProperty("MQTT_REQUEST_TOPIC",   "iot/agent/in");
        System.setProperty("MQTT_RESPONSE_TOPIC",  "iot/agent/out");
        System.setProperty("MQTT_QOS",             "2");
        System.setProperty("MQTT_KEEPALIVE_SECS",  "30");
        System.setProperty("MQTT_CLEAN_SESSION",   "false");

        MqttConfig cfg = MqttConfig.fromEnv();

        assertEquals("ssl://broker.example.com:8883", cfg.brokerUrl());
        assertEquals("mi-agente-01",   cfg.clientId());
        assertEquals("user",           cfg.username());
        assertEquals("pass",           cfg.password());
        assertEquals("iot/agent/in",   cfg.requestTopic());
        assertEquals("iot/agent/out",  cfg.responseTopic());
        assertEquals(2,                cfg.qos());
        assertEquals(30,               cfg.keepAliveSecs());
        assertTrue(!cfg.cleanSession());
    }

    // ── parseInt helper ───────────────────────────────────────────────────────

    @Test
    void parseIntUsesFallbackForInvalidValue() {
        assertEquals(99, MqttConfig.parseInt("abc", 99));
        assertEquals(99, MqttConfig.parseInt("",    99));
        assertEquals(99, MqttConfig.parseInt(null,  99));
        assertEquals(5,  MqttConfig.parseInt("5",   99));
    }
}
