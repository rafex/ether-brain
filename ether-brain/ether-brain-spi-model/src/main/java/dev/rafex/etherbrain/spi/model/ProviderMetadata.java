package dev.rafex.etherbrain.spi.model;

import java.util.List;
import java.util.Map;

/**
 * Metadata describing a model provider.
 * <p>
 * Used by the bootstrap and CLI to present available providers
 * and validate configuration before instantiation.
 */
public record ProviderMetadata(
        String name,
        String description,
        List<String> requiredConfigKeys,
        Map<String, String> defaultConfig
) {
}
