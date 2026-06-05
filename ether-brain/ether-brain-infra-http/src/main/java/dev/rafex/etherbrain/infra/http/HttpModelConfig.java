package dev.rafex.etherbrain.infra.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Configuration for {@link HttpModelClient}: endpoint, credentials, model, limits and headers.
 *
 * <h2>Provider landscape — only two real API formats exist</h2>
 * <ul>
 *   <li><b>OpenAI-compatible</b> (the de-facto standard) — use with
 *       {@link dev.rafex.etherbrain.infra.http.codec.OpenAiCodec}.
 *       Covers: OpenAI, Groq, Deepseek, Mistral, Qwen, OpenRouter, Together AI,
 *       Ollama, LM Studio, vLLM, Fireworks, Perplexity and any provider exposing
 *       {@code /v1/chat/completions}.</li>
 *   <li><b>Anthropic</b> (proprietary) — use with
 *       {@link dev.rafex.etherbrain.infra.http.codec.AnthropicCodec}.</li>
 * </ul>
 *
 * <p>You do <em>not</em> need a new codec per provider — only one per API format.
 *
 * <h2>URL base — el path lo pone el codec</h2>
 * <p>{@code endpoint} debe ser la URL base del proveedor sin path. Cada codec
 * conoce y añade su propio path:
 * <ul>
 *   <li>{@code OpenAiCodec}  → {@code base + /v1/chat/completions}</li>
 *   <li>{@code AnthropicCodec} → {@code base + /v1/messages}</li>
 *   <li>{@code GeminiCodec}  → {@code base + /v1beta/models/{model}:generateContent}</li>
 *   <li>{@code BedrockCodec} → {@code base + /model/{model}/invoke}</li>
 * </ul>
 *
 * <h2>Temperature</h2>
 * <p>Set via {@code LLM_TEMPERATURE} env var or {@link #withTemperature}.
 * Use {@link #TEMPERATURE_UNSET} (the default, {@code -1.0}) to let each codec
 * apply its own default. Supported range: {@code 0.0 – 2.0} (provider-dependent).
 *
 * <h2>Extra headers</h2>
 * Use {@link #withExtraHeaders} to add provider-specific headers, e.g. for OpenRouter:
 * <pre>{@code
 * HttpModelConfig.openRouter(apiKey, model)
 *     .withExtraHeaders(Map.of(
 *         "HTTP-Referer", "https://your-app.com",
 *         "X-Title",      "Your App Name"))
 * }</pre>
 */
public record HttpModelConfig(
        URI endpoint,
        String apiKey,
        String model,
        int maxTokens,
        double temperature,
        Duration timeout,
        Map<String, String> extraHeaders
) {

    /**
     * Sentinel value for {@code temperature}: means "not set — use codec default".
     * Codecs treat any value {@code < 0} as unset and omit or substitute their own default.
     */
    public static final double TEMPERATURE_UNSET = -1.0;

    public HttpModelConfig {
        extraHeaders = (extraHeaders == null) ? Map.of() : Map.copyOf(extraHeaders);
    }

    /** Backward-compatible 5-arg constructor (no temperature, no extra headers). */
    public HttpModelConfig(URI endpoint, String apiKey, String model,
                           int maxTokens, Duration timeout) {
        this(endpoint, apiKey, model, maxTokens, TEMPERATURE_UNSET, timeout, Map.of());
    }

    /** Backward-compatible 6-arg constructor (no temperature). */
    public HttpModelConfig(URI endpoint, String apiKey, String model,
                           int maxTokens, Duration timeout,
                           Map<String, String> extraHeaders) {
        this(endpoint, apiKey, model, maxTokens, TEMPERATURE_UNSET, timeout, extraHeaders);
    }

    // ── Fluent modifiers ──────────────────────────────────────────────────────

    /** Returns a copy with a different {@code maxTokens} limit. */
    public HttpModelConfig withMaxTokens(int tokens) {
        return new HttpModelConfig(endpoint, apiKey, model, tokens, temperature, timeout, extraHeaders);
    }

    /**
     * Returns a copy with the given sampling temperature.
     * Pass {@link #TEMPERATURE_UNSET} to revert to codec default.
     *
     * @param temp sampling temperature (typical range {@code 0.0 – 2.0})
     */
    public HttpModelConfig withTemperature(double temp) {
        return new HttpModelConfig(endpoint, apiKey, model, maxTokens, temp, timeout, extraHeaders);
    }

    /** Returns a copy with additional HTTP headers merged into any existing ones. */
    public HttpModelConfig withExtraHeaders(Map<String, String> headers) {
        var merged = new java.util.HashMap<>(extraHeaders);
        merged.putAll(headers);
        return new HttpModelConfig(endpoint, apiKey, model, maxTokens, temperature, timeout, merged);
    }

    // ── Anthropic ──────────────────────────────────────────────────────────────

    /** Anthropic Claude — use with {@code AnthropicCodec}. */
    public static HttpModelConfig anthropic(String apiKey, String model) {
        return new HttpModelConfig(
                URI.create("https://api.anthropic.com"),
                apiKey, model, 4096, Duration.ofSeconds(60));
    }

    // ── OpenAI-compatible (same codec, different base URL) ────────────────────

    /** OpenAI — use with {@code OpenAiCodec}. */
    public static HttpModelConfig openAi(String apiKey, String model) {
        return openAiCompatible(URI.create("https://api.openai.com"), apiKey, model);
    }

    /** Groq — use with {@code OpenAiCodec}. Models: {@code llama-3.3-70b-versatile}. */
    public static HttpModelConfig groq(String apiKey, String model) {
        return openAiCompatible(URI.create("https://api.groq.com"), apiKey, model);
    }

    /** Deepseek — use with {@code OpenAiCodec}. Models: {@code deepseek-chat}. */
    public static HttpModelConfig deepseek(String apiKey, String model) {
        return openAiCompatible(URI.create("https://api.deepseek.com"), apiKey, model);
    }

    /** Mistral AI — use with {@code OpenAiCodec}. Models: {@code mistral-large-latest}. */
    public static HttpModelConfig mistral(String apiKey, String model) {
        return openAiCompatible(URI.create("https://api.mistral.ai"), apiKey, model);
    }

    /** Cerebras — use with {@code OpenAiCodec}. Models: {@code gpt-oss-120b}. */
    public static HttpModelConfig cerebras(String apiKey, String model) {
        return openAiCompatible(URI.create("https://api.cerebras.ai"), apiKey, model);
    }

    /**
     * OpenRouter — enruta a cualquier modelo. Use with {@code OpenAiCodec}.
     * Formato del modelo: {@code "anthropic/claude-opus-4-5"}, {@code "deepseek/deepseek-chat"}.
     */
    public static HttpModelConfig openRouter(String apiKey, String model) {
        return openAiCompatible(URI.create("https://openrouter.ai"), apiKey, model);
    }

    /** Ollama local — use with {@code OpenAiCodec}. Sin API key. */
    public static HttpModelConfig ollama(String model) {
        return ollama("http://localhost:11434", model);
    }

    /** Ollama en host personalizado — use with {@code OpenAiCodec}. */
    public static HttpModelConfig ollama(String baseUrl, String model) {
        return openAiCompatible(
                URI.create(baseUrl.replaceAll("/+$", "")), "", model);
    }

    /**
     * Cualquier endpoint OpenAI-compatible — use with {@code OpenAiCodec}.
     * {@code endpoint} es la URL base del proveedor (sin path).
     */
    public static HttpModelConfig openAiCompatible(URI endpoint, String apiKey, String model) {
        return new HttpModelConfig(endpoint, apiKey, model, 4096, Duration.ofSeconds(60));
    }
}
