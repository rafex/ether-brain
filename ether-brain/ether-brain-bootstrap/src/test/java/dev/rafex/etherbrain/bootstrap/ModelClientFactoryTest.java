package dev.rafex.etherbrain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rafex.etherbrain.infra.http.ProviderCodec;
import dev.rafex.etherbrain.infra.http.codec.AnthropicCodec;
import dev.rafex.etherbrain.infra.http.codec.BedrockCodec;
import dev.rafex.etherbrain.infra.http.codec.GeminiCodec;
import dev.rafex.etherbrain.infra.http.codec.OpenAiCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelClientFactoryTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("LLM_TYPE");
        System.clearProperty("LLM_URL");
        System.clearProperty("LLM_MODEL");
    }

    // ── resolveCodec — LLM_TYPE explícito ────────────────────────────────────

    @Test
    void resolvesOpenAiCodecByType() {
        System.setProperty("LLM_TYPE", "openai");
        assertInstanceOf(OpenAiCodec.class, resolveCodec("https://api.openai.com"));
    }

    @Test
    void resolvesAnthropicCodecByType() {
        System.setProperty("LLM_TYPE", "anthropic");
        assertInstanceOf(AnthropicCodec.class, resolveCodec("https://example.com"));
    }

    @Test
    void resolvesGeminiCodecByType() {
        System.setProperty("LLM_TYPE", "gemini");
        assertInstanceOf(GeminiCodec.class, resolveCodec("https://example.com"));
    }

    @Test
    void resolvesBedrockCodecByType() {
        System.setProperty("LLM_TYPE", "bedrock");
        assertInstanceOf(BedrockCodec.class, resolveCodec("https://example.com"));
    }

    @Test
    void throwsForUnknownLlmType() {
        System.setProperty("LLM_TYPE", "unknown-provider");
        assertThrows(IllegalArgumentException.class,
                () -> resolveCodec("https://example.com"));
    }

    // ── resolveCodec — inferencia por hostname ────────────────────────────────

    @Test
    void infersAnthropicFromHostname() {
        assertInstanceOf(AnthropicCodec.class,
                resolveCodec("https://api.anthropic.com/v1/messages"));
    }

    @Test
    void infersGeminiFromHostname() {
        assertInstanceOf(GeminiCodec.class,
                resolveCodec("https://generativelanguage.googleapis.com"));
    }

    @Test
    void infersGeminiFromAiPlatform() {
        assertInstanceOf(GeminiCodec.class,
                resolveCodec("https://us-central1-aiplatform.googleapis.com"));
    }

    @Test
    void infersBedrockFromHostname() {
        assertInstanceOf(BedrockCodec.class,
                resolveCodec("https://bedrock-runtime.us-east-1.amazonaws.com"));
    }

    @Test
    void defaultsToOpenAiForUnknownUrl() {
        assertInstanceOf(OpenAiCodec.class,
                resolveCodec("http://localhost:11434"));
    }

    @Test
    void defaultsToOpenAiForOllamaUrl() {
        assertInstanceOf(OpenAiCodec.class,
                resolveCodec("http://localhost:11434/v1/chat/completions"));
    }

    // ── build — returns StubModelClient when LLM_URL absent ──────────────────

    @Test
    void buildReturnsDemoClientWhenNoUrl() {
        // Skip if the OS environment or CI pipeline has LLM_URL set — we
        // cannot clear real environment variables from within the JVM.
        assumeTrue(System.getenv("LLM_URL") == null,
                "Skipped: LLM_URL is set in the OS environment");
        System.clearProperty("LLM_URL"); // also clear any system property
        assertInstanceOf(StubModelClient.class,
                ModelClientFactory.build(java.time.Duration.ofSeconds(10)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ProviderCodec resolveCodec(String url) {
        return ModelClientFactory.resolveCodec(url);
    }
}
