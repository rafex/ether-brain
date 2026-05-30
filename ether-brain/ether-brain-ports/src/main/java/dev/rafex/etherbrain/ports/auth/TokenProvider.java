package dev.rafex.etherbrain.ports.auth;

/**
 * Port for obtaining a bearer or API-key auth token for a remote service.
 *
 * <p>Implementations may return a static value, a cached JWT with auto-refresh,
 * or any other auth credential as a raw string. Callers should treat the returned
 * string as opaque and inspect its prefix to decide which HTTP header to use:
 * <ul>
 *   <li>{@code eyJ…} → {@code Authorization: Bearer <token>} (JWT)</li>
 *   <li>anything else → {@code X-API-Key: <token>}</li>
 * </ul>
 *
 * <p>A static token can be expressed as a lambda:
 * <pre>{@code
 * TokenProvider staticKey = () -> "my-api-key";
 * }</pre>
 */
public interface TokenProvider {

    /**
     * Returns a valid auth token, refreshing it if necessary.
     *
     * @return a non-null, non-blank token string
     * @throws Exception if the token cannot be obtained or refreshed
     */
    String getToken() throws Exception;

    /**
     * Signals that the current cached token is no longer valid (e.g. after a 401).
     * The next call to {@link #getToken()} will force a new token to be fetched.
     * Default implementation is a no-op (suitable for static tokens).
     */
    default void invalidate() {
    }
}
