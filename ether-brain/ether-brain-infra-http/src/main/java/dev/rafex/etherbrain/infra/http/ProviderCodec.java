package dev.rafex.etherbrain.infra.http;

import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import java.net.http.HttpRequest;

public interface ProviderCodec {

    HttpRequest buildHttpRequest(ModelRequest request, HttpModelConfig config);

    ModelResponse parseResponse(String responseBody);
}
