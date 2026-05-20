package dev.rafex.etherbrain.spi.model;

import dev.rafex.etherbrain.ports.model.ModelClient;
import java.util.Map;

/**
 * Service Provider Interface for discovering and creating {@link ModelClient} instances.
 * <p>
 * Each provider (OpenAI, Ollama, Anthropic, Demo, etc.) implements this interface
 * and registers itself via {@code META-INF/services/dev.rafex.etherbrain.spi.model.ModelClientFactory}.
 * <p>
 * Discovered at runtime via {@link java.util.ServiceLoader}.
 */
public interface ModelClientFactory {

    /**
     * Unique provider name used for CLI selection and logging.
     */
    String name();

    /**
     * Descriptive metadata for this provider.
     */
    ProviderMetadata metadata();

    /**
     * Creates a {@link ModelClient} with the given configuration.
     *
     * @param config key-value configuration specific to this provider
     *               (e.g., API key, base URL, model name)
     * @return a configured model client ready to use
     * @throws IllegalArgumentException if required config is missing
     */
    ModelClient create(Map<String, String> config);
}
