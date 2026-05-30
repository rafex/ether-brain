package dev.rafex.etherbrain.infra.http;

import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

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
            LOG.log(System.Logger.Level.INFO, "Calling provider at {0}", config.endpoint());

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
}
