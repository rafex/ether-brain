package dev.rafex.etherbrain.infra.model.demo;

import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.spi.model.ModelClientFactory;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.util.List;
import java.util.Map;

/**
 * Factory for the demo model client.
 * <p>
 * Requires no configuration. Always returns a deterministic,
 * rule-based client for development and testing.
 */
public final class DemoModelClientFactory implements ModelClientFactory {

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public ProviderMetadata metadata() {
        return new ProviderMetadata(
                "demo",
                "Deterministic demo client for development and testing. No external API needed.",
                List.of(),
                Map.of()
        );
    }

    @Override
    public ModelClient create(Map<String, String> config) {
        return new DemoModelClient();
    }
}
