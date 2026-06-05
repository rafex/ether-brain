package dev.rafex.etherbrain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.rafex.etherbrain.infra.file.FileSessionStore;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreFactoryTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("SESSION_DIR");
        System.clearProperty("SESSION_TTL_HOURS");
    }

    @Test
    void returnsInMemoryStoreWhenSessionDirNotSet() {
        assertInstanceOf(InMemorySessionStore.class, SessionStoreFactory.build());
    }

    @Test
    void returnsFileStoreWhenSessionDirIsSet(@TempDir Path dir) {
        System.setProperty("SESSION_DIR", dir.toString());
        assertInstanceOf(FileSessionStore.class, SessionStoreFactory.build());
    }

    @Test
    void fileStoreIsNotNullWithTtl(@TempDir Path dir) {
        System.setProperty("SESSION_DIR", dir.toString());
        System.setProperty("SESSION_TTL_HOURS", "24");
        assertNotNull(SessionStoreFactory.build());
    }
}
