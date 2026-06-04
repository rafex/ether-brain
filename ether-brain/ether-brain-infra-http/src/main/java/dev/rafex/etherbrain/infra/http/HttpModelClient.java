package dev.rafex.etherbrain.infra.http;

import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

public final class HttpModelClient implements ModelClient {

    private static final System.Logger LOG = System.getLogger(HttpModelClient.class.getName());

    private final HttpClient httpClient;
    private final HttpModelConfig config;
    private final ProviderCodec codec;

    public HttpModelClient(HttpModelConfig config, ProviderCodec codec) {
        this.config = config;
        this.codec = codec;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        try {
            var httpRequest = codec.buildHttpRequest(request, config);
            // Loguear la URI real del request, no la URL base de config
            LOG.log(System.Logger.Level.INFO, "Calling provider at {0}", httpRequest.uri());

            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int status = httpResponse.statusCode();
            if (status != 200) {
                throw new RuntimeException(
                        "Provider returned HTTP %d: %s".formatted(status, httpResponse.body()));
            }

            return codec.parseResponse(httpResponse.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call model provider", e);
        }
    }

    // ── Streaming call ────────────────────────────────────────────────────────

    /**
     * Calls the provider in streaming mode, emitting each text token to {@code onToken}
     * as it arrives. Delegates to the codec's {@link ProviderCodec#generateStreaming},
     * which handles the provider-specific SSE / chunked format.
     *
     * <p>If the codec does not override {@link ProviderCodec#generateStreaming} the
     * default fallback is used: one blocking call, full content emitted as one token.
     */
    @Override
    public ModelResponse generateStreaming(ModelRequest request,
                                           Consumer<String> onToken) throws Exception {
        try {
            LOG.log(System.Logger.Level.INFO,
                    "Streaming call — provider streaming={0}, model={1}",
                    codec.supportsStreaming(), config.model());
            return codec.generateStreaming(request, config, httpClient, onToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Streaming call interrupted", e);
        }
    }

    @Override
    public boolean supportsStreaming() { return codec.supportsStreaming(); }
}
