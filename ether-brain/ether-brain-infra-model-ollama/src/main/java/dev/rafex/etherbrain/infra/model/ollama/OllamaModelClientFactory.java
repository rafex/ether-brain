package dev.rafex.etherbrain.infra.model.ollama;

import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.spi.model.ModelClientFactory;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.util.List;
import java.util.Map;

/**
 * Factory for Ollama model clients.
 * <p>
 * Requires no API key. Connects to a local Ollama instance.
 * Default model is {@code llama3.2}.
 */
public final class OllamaModelClientFactory implements ModelClientFactory {

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public ProviderMetadata metadata() {
        return new ProviderMetadata(
                "ollama",
                "Local LLM runtime via Ollama (llama3.2, mistral, gemma, etc.). "
                        + "No API key required. Runs completely offline.",
                List.of(),
                Map.of(
                        "OLLAMA_URL", "http://localhost:11434",
                        "OLLAMA_MODEL", "llama3.2"
                )
        );
    }

    @Override
    public ModelClient create(Map<String, String> config) {
        return new OllamaModelClient(config);
    }
}
