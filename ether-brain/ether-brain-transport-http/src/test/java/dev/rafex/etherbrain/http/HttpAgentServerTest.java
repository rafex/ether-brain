package dev.rafex.etherbrain.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import org.junit.jupiter.api.Test;

class HttpAgentServerTest {

    // ── Helper — server instances sin runtime real ────────────────────────────

    /** Crea un servidor sin auth, rate-limit deshabilitado, callbacks privados permitidos. */
    private static HttpAgentServer noAuthServer() {
        return new HttpAgentServer(null, 0, 0, 10, MetricsCollector.noop(),
                null, 65_536, 0, true);
    }

    /** Crea un servidor con Bearer token requerido. */
    private static HttpAgentServer authServer(String token) {
        return new HttpAgentServer(null, 0, 0, 10, MetricsCollector.noop(),
                token, 65_536, 0, false);
    }

    /** Crea un servidor con rate-limit N rpm. */
    private static HttpAgentServer rateLimitedServer(int rpm) {
        return new HttpAgentServer(null, 0, 0, 10, MetricsCollector.noop(),
                null, 65_536, rpm, false);
    }

    /** Crea un servidor que bloquea IPs privadas (SSRF). */
    private static HttpAgentServer ssrfGuardServer() {
        return new HttpAgentServer(null, 0, 0, 10, MetricsCollector.noop(),
                null, 65_536, 0, false);
    }

    /** Crea un servidor que permite IPs privadas en callbacks. */
    private static HttpAgentServer allowPrivateServer() {
        return new HttpAgentServer(null, 0, 0, 10, MetricsCollector.noop(),
                null, 65_536, 0, true);
    }

    // ── extractMessage ────────────────────────────────────────────────────────

    @Test
    void extractsSimpleMessage() {
        assertEquals("¿Quién eres?", HttpAgentServer.extractMessage("{\"message\":\"¿Quién eres?\"}"));
    }

    @Test
    void extractsMessageWithSpaces() {
        assertEquals("Hola mundo", HttpAgentServer.extractMessage("{ \"message\" : \"Hola mundo\" }"));
    }

    @Test
    void extractsMessageWithEscapedQuotes() {
        assertEquals("Dice \"hola\"",
                HttpAgentServer.extractMessage("{\"message\":\"Dice \\\"hola\\\"\"}"));
    }

    @Test
    void returnsNullWhenMessageMissing() {
        assertNull(HttpAgentServer.extractMessage("{\"other\":\"value\"}"));
    }

    @Test
    void returnsNullForEmptyBody() {
        assertNull(HttpAgentServer.extractMessage(""));
        assertNull(HttpAgentServer.extractMessage(null));
    }

    @Test
    void extractsMessageWithOtherFields() {
        assertEquals("¿Qué hora es?", HttpAgentServer.extractMessage(
                "{\"session\":\"s1\",\"message\":\"¿Qué hora es?\",\"extra\":true}"));
    }

    // ── extractField ──────────────────────────────────────────────────────────

    @Test
    void extractsSessionIdField() {
        String json = "{\"session_id\":\"abc-123\",\"message\":\"hi\"}";
        assertEquals("abc-123", HttpAgentServer.extractField(json, "session_id"));
    }

    @Test
    void extractsCallbackUrlField() {
        String json = "{\"message\":\"task\",\"callback_url\":\"https://example.com/hook\"}";
        assertEquals("https://example.com/hook",
                HttpAgentServer.extractField(json, "callback_url"));
    }

    @Test
    void extractFieldReturnsNullForMissingField() {
        assertNull(HttpAgentServer.extractField("{\"a\":\"1\"}", "b"));
    }

    @Test
    void extractFieldHandlesNewlineEscape() {
        String json = "{\"message\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", HttpAgentServer.extractField(json, "message"));
    }

    // ── jsonString ────────────────────────────────────────────────────────────

    @Test
    void jsonStringEscapesNewlines() {
        String result = HttpAgentServer.jsonString("a\nb");
        assertTrue(result.contains("\\n"), "Expected escaped newline, got: " + result);
        assertFalse(result.contains("\n"), "Must not contain raw newline");
    }

    @Test
    void jsonStringEscapesQuotes() {
        String result = HttpAgentServer.jsonString("say \"hi\"");
        assertTrue(result.contains("\\\""));
    }

    @Test
    void jsonStringHandlesNull() {
        assertEquals("null", HttpAgentServer.jsonString(null));
    }

    @Test
    void jsonStringWrapsInDoubleQuotes() {
        String result = HttpAgentServer.jsonString("hello");
        assertEquals("\"hello\"", result);
    }

    // ── isAuthorized ──────────────────────────────────────────────────────────

    @Test
    void noAuthConfiguredAllowsAnyRequest() {
        assertTrue(noAuthServer().isAuthorized(null));
        assertTrue(noAuthServer().isAuthorized(""));
        assertTrue(noAuthServer().isAuthorized("Bearer anything"));
    }

    @Test
    void correctBearerTokenIsAuthorized() {
        assertTrue(authServer("secret123").isAuthorized("Bearer secret123"));
    }

    @Test
    void wrongTokenIsNotAuthorized() {
        assertFalse(authServer("secret123").isAuthorized("Bearer wrongtoken"));
    }

    @Test
    void missingAuthHeaderIsNotAuthorized() {
        assertFalse(authServer("secret123").isAuthorized(null));
    }

    @Test
    void bareTokenWithoutBearerPrefixIsNotAuthorized() {
        assertFalse(authServer("secret123").isAuthorized("secret123"));
    }

    // ── isRateLimited ─────────────────────────────────────────────────────────

    @Test
    void rateLimitDisabledNeverBlocks() {
        HttpAgentServer server = noAuthServer(); // rateLimitRpm=0
        for (int i = 0; i < 100; i++) {
            assertFalse(server.isRateLimited("10.0.0.1"));
        }
    }

    @Test
    void requestsBelowLimitAreAllowed() {
        HttpAgentServer server = rateLimitedServer(5);
        for (int i = 0; i < 5; i++) {
            assertFalse(server.isRateLimited("192.168.1.1"),
                    "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void requestAtLimitIsBlocked() {
        HttpAgentServer server = rateLimitedServer(3);
        server.isRateLimited("1.2.3.4");
        server.isRateLimited("1.2.3.4");
        server.isRateLimited("1.2.3.4"); // fills the window
        assertTrue(server.isRateLimited("1.2.3.4")); // 4th → blocked
    }

    @Test
    void differentIpsHaveIndependentWindows() {
        HttpAgentServer server = rateLimitedServer(2);
        server.isRateLimited("10.0.0.1");
        server.isRateLimited("10.0.0.1"); // fills 10.0.0.1 window
        // 10.0.0.2 should still be allowed
        assertFalse(server.isRateLimited("10.0.0.2"));
    }

    // ── isSafeCallbackUrl (SSRF) ──────────────────────────────────────────────

    @Test
    void publicHttpsUrlIsSafe() {
        // 1.1.1.1 (Cloudflare) — public IP, no DNS lookup required
        assertTrue(ssrfGuardServer().isSafeCallbackUrl("https://1.1.1.1/webhook"));
    }

    @Test
    void publicHttpUrlIsSafe() {
        // 8.8.8.8 (Google) — public IP, no DNS lookup required
        assertTrue(ssrfGuardServer().isSafeCallbackUrl("http://8.8.8.8/hook"));
    }

    @Test
    void loopbackIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("http://127.0.0.1/hook"));
    }

    @Test
    void localhostIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("http://localhost/hook"));
    }

    @Test
    void rfc1918_10xIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("http://10.0.0.1/hook"));
    }

    @Test
    void rfc1918_192168IsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("http://192.168.1.1/hook"));
    }

    @Test
    void fileSchemeIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("file:///etc/passwd"));
    }

    @Test
    void nullUrlIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl(null));
    }

    @Test
    void blankUrlIsNotSafe() {
        assertFalse(ssrfGuardServer().isSafeCallbackUrl("   "));
    }

    @Test
    void privateUrlAllowedWhenFlagSet() {
        // allowPrivateCallback=true bypasses SSRF guard
        assertTrue(allowPrivateServer().isSafeCallbackUrl("http://10.0.0.1/hook"));
    }
}
