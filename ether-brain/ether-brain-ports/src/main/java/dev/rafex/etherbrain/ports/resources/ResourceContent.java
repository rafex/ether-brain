package dev.rafex.etherbrain.ports.resources;

public record ResourceContent(
        String uri,
        String mimeType,
        String content
) {
}
