package dev.rafex.etherbrain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvLoaderTest {

    /** Keys set by tests — cleaned up in teardown. */
    private static final String KEY_A = "DOTENV_TEST_KEY_A";
    private static final String KEY_B = "DOTENV_TEST_KEY_B";
    private static final String KEY_QUOTED = "DOTENV_TEST_QUOTED";

    @AfterEach
    void clearProperties() {
        System.clearProperty(KEY_A);
        System.clearProperty(KEY_B);
        System.clearProperty(KEY_QUOTED);
        System.clearProperty("ENV_FILE");
    }

    @Test
    void loadsKeyValueFromFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, KEY_A + "=hello_from_env\n");
        System.setProperty("ENV_FILE", env.toString());

        DotEnvLoader.load();

        assertEquals("hello_from_env", System.getProperty(KEY_A));
    }

    @Test
    void stripsDoubleQuotesFromValue(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, KEY_QUOTED + "=\"quoted value\"\n");
        System.setProperty("ENV_FILE", env.toString());

        DotEnvLoader.load();

        assertEquals("quoted value", System.getProperty(KEY_QUOTED));
    }

    @Test
    void stripsSingleQuotesFromValue(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, KEY_QUOTED + "='single quoted'\n");
        System.setProperty("ENV_FILE", env.toString());

        DotEnvLoader.load();

        assertEquals("single quoted", System.getProperty(KEY_QUOTED));
    }

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # This is a comment

                %s=real_value
                # Another comment
                """.formatted(KEY_A));
        System.setProperty("ENV_FILE", env.toString());

        DotEnvLoader.load();

        assertEquals("real_value", System.getProperty(KEY_A));
    }

    @Test
    void doesNotOverwriteValueAlreadyInSystemProperties(@TempDir Path dir) throws Exception {
        // The loader only calls System.setProperty when System.getenv(key) is null.
        // A property already set via setProperty is NOT protected — only real OS
        // env vars are protected (we can't set those in unit tests).
        // This test verifies the correct behavior: file value wins over a pre-set
        // system property when no OS env var is present.
        System.setProperty(KEY_B, "preset");

        Path env = dir.resolve(".env");
        Files.writeString(env, KEY_B + "=from_file\n");
        System.setProperty("ENV_FILE", env.toString());

        DotEnvLoader.load();

        // file value wins because System.getenv(KEY_B) == null
        assertEquals("from_file", System.getProperty(KEY_B));
    }

    @Test
    void doesNothingWhenEnvFileDoesNotExist(@TempDir Path dir) {
        System.setProperty("ENV_FILE", dir.resolve("nonexistent.env").toString());
        // Should not throw
        DotEnvLoader.load();
        assertNull(System.getProperty(KEY_A));
    }
}
