package dev.rafex.etherbrain.ports.model;

/**
 * Port of exit: sends a {@link ModelRequest} to a language model provider
 * and returns a {@link ModelResponse}.
 *
 * <p>Implementations are provider-specific adapters (Anthropic, OpenAI, local LLMs).
 * The domain never depends on a concrete implementation; it only interacts
 * with this interface.
 *
 * @see ModelRequest
 * @see ModelResponse
 * @see FinalAnswer
 * @see ToolRequest
 */
public interface ModelClient {

    /**
     * Sends the request to the model and returns the response.
     *
     * @param request the fully built request including system prompt, history and available tools
     * @return {@link FinalAnswer} when the model produces a final response,
     *         or {@link ToolRequest} when the model wants to invoke a tool
     * @throws Exception if the provider call fails or the response cannot be parsed
     */
    ModelResponse generate(ModelRequest request) throws Exception;
}
