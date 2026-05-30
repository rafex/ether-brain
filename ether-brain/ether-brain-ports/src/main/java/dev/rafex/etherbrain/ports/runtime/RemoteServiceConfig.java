package dev.rafex.etherbrain.ports.runtime;

import java.net.URI;

public record RemoteServiceConfig(String name, URI baseUri, String apiKey) {

    public static RemoteServiceConfig of(String name, String baseUri, String apiKey) {
        return new RemoteServiceConfig(name, URI.create(baseUri), apiKey);
    }

    public static RemoteServiceConfig withoutKey(String name, String baseUri) {
        return new RemoteServiceConfig(name, URI.create(baseUri), "");
    }
}
