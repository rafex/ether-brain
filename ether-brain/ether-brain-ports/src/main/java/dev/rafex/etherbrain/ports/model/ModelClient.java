package dev.rafex.etherbrain.ports.model;

import java.util.function.Consumer;

/**
 * Port of exit: sends a {@link ModelRequest} to a language model provider
 * and returns a {@link ModelResponse}.
 *
 * <p>Implementations are provider-specific adapters (Anthropic, OpenAI, local LLMs).
 * The domain never depends on a concrete implementation; it only interacts
 * with this interface.
 *
 * <h2>Streaming</h2>
 * {@link #generateStreaming(ModelRequest, Consumer)} emits each text token to
 * {@code onToken} as it arrives from the provider (SSE / chunked transfer).
 * This enables the HTTP transport layer to forward tokens to the client in real
 * time without waiting for the full response.
 *
 * <p>The default implementation falls back to {@link #generate(ModelRequest)} and
 * emits the entire content as a single token, so non-streaming clients remain
 * fully compatible.
 *
 * @see ModelRequest
 * @see ModelResponse
 * @see FinalAnswer
 * @see ToolRequest
 */
public interface ModelClient {

    /**
     * Sends the request to the model and returns the response (blocking).
     *
     * @param request the fully built request including system prompt, history and available tools
     * @return {@link FinalAnswer} when the model produces a final response,
     *         or {@link ToolRequest}/{@link BatchedToolRequest} when tool invocation is requested
     * @throws Exception if the provider call fails or the response cannot be parsed
     */
    ModelResponse generate(ModelRequest request) throws Exception;

    /**
     * Sends the request to the model, calling {@code onToken} for each text token
     * as it streams from the provider, and returns the final response.
     *
     * <p>For tool call responses, {@code onToken} is not called (there are no visible
     * text tokens); the tool call is returned directly as {@link ToolRequest} or
     * {@link BatchedToolRequest}.
     *
     * <p><b>Default implementation</b>: falls back to {@link #generate(ModelRequest)}
     * and emits the full content as one chunk. Override for true token streaming.
     *
     * @param request  the model request
     * @param onToken  called for each text token; must be non-throwing
     * @return the final model response
     * @throws Exception if the provider call fails
     */
    default ModelResponse generateStreaming(ModelRequest request,
                                            Consumer<String> onToken) throws Exception {
        ModelResponse response = generate(request);
        if (response instanceof FinalAnswer fa && !fa.content().isEmpty()) {
            onToken.accept(fa.content());
        }
        return response;
    }

    /**
     * Returns {@code true} if this client supports real token-by-token streaming.
     * Used by {@code AgentLoop} to decide whether to call {@link #generateStreaming}.
     * Default: {@code false}.
     */
    default boolean supportsStreaming() { return false; }
}
