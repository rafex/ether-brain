package dev.rafex.etherbrain.bootstrap;

import dev.rafex.ether.logging.core.logger.EtherLog;
import dev.rafex.etherbrain.infra.http.HttpModelClient;
import dev.rafex.etherbrain.infra.http.HttpModelConfig;
import dev.rafex.etherbrain.infra.http.ProviderCodec;
import dev.rafex.etherbrain.infra.http.codec.AnthropicCodec;
import dev.rafex.etherbrain.infra.http.codec.BedrockCodec;
import dev.rafex.etherbrain.infra.http.codec.GeminiCodec;
import dev.rafex.etherbrain.infra.http.codec.OpenAiCodec;
import dev.rafex.etherbrain.ports.model.ModelClient;
import java.net.URI;
import java.time.Duration;

/**
 * Constructs the {@link ModelClient} from environment variables.
 *
 * <h2>Required variables</h2>
 * <pre>
 * LLM_URL        — base URL of the provider (required if a real LLM is used)
 * LLM_TOKEN      — API key / Bearer token (may be empty for local models)
 * LLM_MODEL      — model name
 * LLM_TYPE        — codec selector: openai | anthropic | gemini | bedrock
 *                   (inferred from hostname when absent)
 * LLM_MAX_TOKENS  — max tokens for model response (default: 4096)
 * LLM_TEMPERATURE — sampling temperature, e.g. 0.7 (default: codec-specific)
 * </pre>
 *
 * <p>When {@code LLM_URL} is absent the factory returns a {@link StubModelClient}
 * that works without a real LLM (useful for integration tests and demos).
 */
public final class ModelClientFactory {

    private ModelClientFactory() {}

    /**
     * Builds a {@link ModelClient} from environment variables.
     *
     * @param timeout request timeout applied to both HTTP calls and the
     *                AgentLoop's future-based fallback
     */
    public static ModelClient build(Duration timeout) {
        String llmUrl   = env("LLM_URL",   null);
        String llmToken = env("LLM_TOKEN", "");
        String llmModel = env("LLM_MODEL", "");

        if (llmUrl == null || llmUrl.isBlank()) {
            EtherLog.warn(ModelClientFactory.class,
                    "LLM_URL no definida — modo stub (sin LLM real).");
            return new StubModelClient();
        }
        if (llmModel.isBlank()) {
            throw new IllegalStateException(
                    "LLM_URL está definida pero falta LLM_MODEL.");
        }

        int    maxTokens   = (int) parseLong(env("LLM_MAX_TOKENS",  "4096"), 4096);
        double temperature = parseDouble(env("LLM_TEMPERATURE", ""),
                                         HttpModelConfig.TEMPERATURE_UNSET);

        HttpModelConfig config = new HttpModelConfig(
                URI.create(llmUrl), llmToken, llmModel, maxTokens, timeout);

        if (temperature >= 0) {
            config = config.withTemperature(temperature);
        }

        ProviderCodec codec   = resolveCodec(llmUrl);
        String        llmType = env("LLM_TYPE", "openai");
        EtherLog.info(ModelClientFactory.class,
                "LLM → {} | tipo={} | modelo={} | maxTokens={} | temp={} | timeout={}s",
                llmUrl, llmType, llmModel, maxTokens,
                temperature < 0 ? "default" : temperature,
                timeout.toSeconds());

        return new HttpModelClient(config, codec);
    }

    /**
     * Resolves the {@link ProviderCodec} with this priority chain:
     * <ol>
     *   <li>{@code LLM_TYPE} explicit</li>
     *   <li>Inference from {@code LLM_URL} hostname</li>
     *   <li>{@link OpenAiCodec} as default</li>
     * </ol>
     */
    static ProviderCodec resolveCodec(String llmUrl) {
        String llmType = env("LLM_TYPE", "").toLowerCase();

        if (!llmType.isBlank()) {
            return switch (llmType) {
                case "openai"    -> new OpenAiCodec();
                case "anthropic" -> new AnthropicCodec();
                case "gemini"    -> new GeminiCodec();
                case "bedrock"   -> new BedrockCodec();
                default -> throw new IllegalArgumentException(
                        "LLM_TYPE=\"" + llmType + "\" no reconocido. " +
                        "Valores válidos: openai | anthropic | gemini | bedrock");
            };
        }

        String host = llmUrl.toLowerCase();
        if (host.contains("anthropic.com")) {
            EtherLog.warn(ModelClientFactory.class,
                    "LLM_TYPE no definido — inferido 'anthropic'. Añade LLM_TYPE=anthropic.");
            return new AnthropicCodec();
        }
        if (host.contains("generativelanguage.googleapis.com") ||
                host.contains("aiplatform.googleapis.com")) {
            EtherLog.warn(ModelClientFactory.class,
                    "LLM_TYPE no definido — inferido 'gemini'. Añade LLM_TYPE=gemini.");
            return new GeminiCodec();
        }
        if (host.contains("amazonaws.com")) {
            EtherLog.warn(ModelClientFactory.class,
                    "LLM_TYPE no definido — inferido 'bedrock'. Añade LLM_TYPE=bedrock.");
            return new BedrockCodec();
        }

        return new OpenAiCodec();   // default — covers the majority of providers
    }

    // ── Env helpers (package-private for testability) ─────────────────────────

    static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }

    static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; }
    }
}
