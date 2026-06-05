package dev.rafex.etherbrain.bootstrap;

import dev.rafex.ether.logging.core.logger.EtherLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a {@code .env} file into {@link System#setProperty} so that
 * environment variables can be declared in a local file during development.
 *
 * <h2>Priority</h2>
 * Real OS environment variables always win over values from the file.
 * The file is only a fallback for local development.
 *
 * <h2>Search order</h2>
 * <ol>
 *   <li>{@code ENV_FILE} — explicit path</li>
 *   <li>{@code .env} in the current working directory</li>
 *   <li>{@code ../.env} — useful when running from a Maven sub-module</li>
 * </ol>
 *
 * <h2>Format</h2>
 * <pre>
 * # comment
 * LLM_TYPE=anthropic
 * LLM_URL=https://api.anthropic.com
 * LLM_TOKEN="sk-ant-..."   # optional quotes
 * </pre>
 */
public final class DotEnvLoader {

    private DotEnvLoader() {}

    /** Loads the first {@code .env} file found in the standard locations. */
    public static void load() {
        Path envFile = resolve();
        if (envFile == null) return;

        try {
            int loaded = 0;
            for (String line : Files.readAllLines(envFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key   = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();

                // Strip optional surrounding quotes: "value" or 'value'
                if (value.length() >= 2 &&
                        ((value.startsWith("\"") && value.endsWith("\"")) ||
                         (value.startsWith("'")  && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }

                // Real OS env vars take priority
                if (System.getenv(key) == null) {
                    System.setProperty(key, value);
                    loaded++;
                }
            }
            if (loaded > 0) {
                EtherLog.info(DotEnvLoader.class,
                        ".env cargado: {} ({} variables)", envFile, loaded);
            }
        } catch (IOException e) {
            EtherLog.warn(DotEnvLoader.class,
                    "No se pudo leer .env: {} — {}", envFile, e.getMessage());
        }
    }

    private static Path resolve() {
        // Check both real env vars and system properties (the latter lets tests
        // and the bootstrap itself override the location without OS env vars)
        String explicit = System.getenv("ENV_FILE");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getProperty("ENV_FILE");
        }
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.exists(p)) return p;
            EtherLog.warn(DotEnvLoader.class, "ENV_FILE definido pero no existe: {}", p);
            return null;
        }
        Path cwd = Path.of(".env");
        if (Files.exists(cwd)) return cwd;
        Path parent = Path.of("../.env");
        if (Files.exists(parent)) return parent;
        return null;
    }
}
