package dev.rafex.etherbrain.bootstrap;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.infra.file.FileSessionStore;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import dev.rafex.etherbrain.ports.session.SessionStore;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Constructs the {@link SessionStore} from environment variables.
 *
 * <pre>
 * SESSION_DIR       — directory for persistent sessions (omit = in-memory)
 * SESSION_TTL_HOURS — session lifetime in hours; 0 = unlimited (default: 0)
 * </pre>
 */
public final class SessionStoreFactory {

    private SessionStoreFactory() {}

    public static SessionStore build() {
        String dir = env("SESSION_DIR", null);
        if (dir == null || dir.isBlank()) {
            EtherLog.info(SessionStoreFactory.class,
                    "SESSION_DIR no definido — sesiones en memoria (no persistentes).");
            return new InMemorySessionStore();
        }

        long ttlHours = parseLong(env("SESSION_TTL_HOURS", "0"), 0);
        Duration ttl = ttlHours > 0
                ? Duration.ofHours(ttlHours)
                : FileSessionStore.NO_TTL;

        EtherLog.info(SessionStoreFactory.class,
                "Sesiones persistentes en {} (TTL={})",
                dir, ttlHours > 0 ? ttlHours + "h" : "sin límite");

        return new FileSessionStore(Path.of(dir), ttl);
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }
}
