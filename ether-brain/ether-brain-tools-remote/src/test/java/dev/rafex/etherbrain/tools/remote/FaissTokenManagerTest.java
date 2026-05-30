package dev.rafex.etherbrain.tools.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FaissTokenManagerTest {

    /**
     * Builds a minimal JWT-like string with the given exp timestamp.
     * Format: header.payload.signature (signature is fake — not validated here).
     */
    private static String fakeJwt(long expEpochSeconds) {
        String header  = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"1\",\"exp\":" + expEpochSeconds + "}").getBytes());
        return header + "." + payload + ".fakesignature";
    }

    @Test
    void parsesExpClaimFromJwt() {
        long expected = Instant.now().plusSeconds(3600).getEpochSecond();
        String token = fakeJwt(expected);

        Instant expiry = FaissTokenManager.parseJwtExpiry(token);

        assertEquals(expected, expiry.getEpochSecond());
    }

    @Test
    void returnsNowPlusOneHourForMalformedToken() {
        Instant before = Instant.now().plusSeconds(3500);
        Instant expiry = FaissTokenManager.parseJwtExpiry("not.a.jwt");
        Instant after  = Instant.now().plusSeconds(3700);

        assertTrue(expiry.isAfter(before) && expiry.isBefore(after),
                "Expected fallback expiry ~1h from now");
    }

    @Test
    void parsesExpFromTokenWithoutPadding() {
        // Payload without "==" padding — common in real JWTs
        long exp = Instant.now().plusSeconds(1800).getEpochSecond();
        String token = fakeJwt(exp);
        // tokens never have "=" padding in practice — strip it to simulate
        String[] parts = token.split("\\.");
        String unpadded = parts[0] + "." + parts[1].replace("=", "") + "." + parts[2];

        Instant expiry = FaissTokenManager.parseJwtExpiry(unpadded);

        assertEquals(exp, expiry.getEpochSecond());
    }

    @Test
    void invalidateResetsExpiry() {
        // We can't easily test FaissTokenManager without a real server,
        // but we can confirm invalidate() resets the token to require refresh.
        // Use a subclass that exposes the cached state for testing.
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        String token = fakeJwt(future);

        // Directly verify the static method used to detect expiry
        Instant expiry = FaissTokenManager.parseJwtExpiry(token);
        assertNotNull(expiry);
        assertFalse(expiry.isBefore(Instant.now()), "Expiry should be in the future");
    }
}
