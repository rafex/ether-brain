package dev.rafex.etherbrain.ports.resources;

public record ResourceDescriptor(
        String uri,
        String name,
        String description,
        String mimeType
) {
}
