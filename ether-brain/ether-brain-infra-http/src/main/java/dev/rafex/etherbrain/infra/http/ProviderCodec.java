package dev.rafex.etherbrain.infra.http;

import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/**
 * Codec that translates between EtherBrain's internal model contract
 * ({@link ModelRequest} / {@link ModelResponse}) and a specific provider's
 * HTTP wire format.
 *
 * <h2>Streaming</h2>
 * Codecs that support real token streaming override {@link #generateStreaming}.
 * The default implementation calls the non-streaming endpoint and emits the
 * full response content as a single token — safe for providers that do not
 * expose SSE.
 */
public interface ProviderCodec {

    /** Build the HTTP request for a blocking (non-streaming) call. */
    java.net.http.HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config);

    /** Parse the full response body of a blocking call. */
    ModelResponse parseResponse(String responseBody);

    /**
     * Execute a streaming call: emits each text token to {@code onToken} as it
     * arrives and returns the final {@link ModelResponse}.
     *
     * <p><b>Default implementation</b>: falls back to a regular blocking call via
     * {@link #buildHttpRequest} / {@link #parseResponse}, emitting the full response
     * content as one token. Override for true chunk-by-chunk streaming.
     *
     * @param request    model request
     * @param config     HTTP client configuration (URL, token, model, timeout…)
     * @param httpClient the shared HTTP client to use for the call
     * @param onToken    called for each text token; must be non-throwing
     * @return the final model response (FinalAnswer, ToolRequest, or BatchedToolRequest)
     */
    default ModelResponse generateStreaming(ModelRequest request,
                                            HttpModelConfig config,
                                            HttpClient httpClient,
                                            Consumer<String> onToken) throws Exception {
        // Fallback: blocking call
        var httpRequest = buildHttpRequest(request, config);
        var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Provider returned HTTP %d: %s".formatted(response.statusCode(), response.body()));
        }
        ModelResponse result = parseResponse(response.body());
        if (result instanceof FinalAnswer fa && !fa.content().isEmpty()) {
            onToken.accept(fa.content()); // emit entire content as one chunk
        }
        return result;
    }

    /**
     * Returns {@code true} if this codec implements real token streaming
     * (i.e. overrides {@link #generateStreaming} with an SSE-based implementation).
     * Default: {@code false}.
     */
    default boolean supportsStreaming() { return false; }
}
