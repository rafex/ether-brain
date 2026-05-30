package dev.rafex.etherbrain.infra.http;

import java.net.URI;
import java.time.Duration;

public record HttpModelConfig(
        URI endpoint,
        String apiKey,
        String model,
        int maxTokens,
        Duration timeout
) {

    public static HttpModelConfig anthropic(String apiKey, String model) {
        return new HttpModelConfig(
                URI.create("https://api.anthropic.com/v1/messages"),
                apiKey,
                model,
                1024,
                Duration.ofSeconds(30)
        );
    }

    public static HttpModelConfig openAi(String apiKey, String model) {
        return new HttpModelConfig(
                URI.create("https://api.openai.com/v1/chat/completions"),
                apiKey,
                model,
                1024,
                Duration.ofSeconds(30)
        );
    }

    public static HttpModelConfig openAiCompatible(URI endpoint, String apiKey, String model) {
        return new HttpModelConfig(endpoint, apiKey, model, 1024, Duration.ofSeconds(30));
    }
}
