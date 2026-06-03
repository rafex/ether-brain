package dev.rafex.etherbrain.infra.model.openai;

import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.spi.model.ModelClientFactory;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.util.List;
import java.util.Map;

/**
 * Factory for OpenAI-compatible model clients.
 * <p>
 * Requires {@code OPENAI_API_KEY} in configuration.
 * Supports any OpenAI-compatible endpoint (Azure, local proxies, etc.).
 */
public final class OpenAiModelClientFactory implements ModelClientFactory {

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public ProviderMetadata metadata() {
        return new ProviderMetadata(
                "openai",
                "OpenAI Chat Completions API (GPT-4o, GPT-4o-mini, etc.). "
                        + "Also compatible with Azure OpenAI and local proxies.",
                List.of("OPENAI_API_KEY"),
                Map.of(
                        "OPENAI_BASE_URL", "https://api.openai.com",
                        "OPENAI_MODEL", "gpt-4o-mini"
                )
        );
    }

    @Override
    public ModelClient create(Map<String, String> config) {
        return new OpenAiModelClient(config);
    }
}
