package dev.rafex.etherbrain.ports.model;

public interface ModelClient {

    ModelResponse generate(ModelRequest request) throws Exception;
}
