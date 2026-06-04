package dev.rafex.etherbrain.ports.runtime;

/**
 * Observer that receives real-time progress events from an agent loop.
 *
 * <p>Used by {@code AgentLoop} to push step-by-step progress to the caller
 * (e.g. an SSE endpoint) without requiring true LLM token streaming.
 *
 * <h2>Event sequence for a 2-step run</h2>
 * <pre>
 * onStepStart(1)
 * onToolCall(1, "search_web", "{\"query\":\"EtherBrain\"}")
 * onToolResult(1, "search_web", true, "EtherBrain is a Java 21 agent runtime…")
 * onStepStart(2)
 * onFinalAnswer("Based on the search, EtherBrain is…")
 * </pre>
 *
 * <h2>Error handling</h2>
 * Implementations must be non-throwing — any exception thrown by a listener
 * method is caught and logged by {@code AgentLoop} so it cannot abort the run.
 */
public interface StepListener {

    /**
     * Called at the beginning of each ReAct step before the model is invoked.
     *
     * @param step 1-based step number
     */
    void onStepStart(int step);

    /**
     * Called when the model requests a tool execution.
     *
     * @param step      current step number
     * @param toolName  name of the tool being called
     * @param arguments raw JSON arguments
     */
    void onToolCall(int step, String toolName, String arguments);

    /**
     * Called after a tool finishes executing (success or failure).
     *
     * @param step     current step number
     * @param toolName name of the tool that ran
     * @param success  whether the tool succeeded
     * @param result   result content (may be an error message if {@code !success})
     */
    void onToolResult(int step, String toolName, boolean success, String result);

    /**
     * Called when the model returns a final answer and the loop ends normally.
     *
     * @param answer the agent's final answer
     */
    void onFinalAnswer(String answer);

    /**
     * Called when the loop ends with an error (timeout, max steps, cancellation…).
     *
     * @param error description of the failure
     */
    void onError(String error);

    /**
     * Called for each text token emitted by the model during streaming.
     * Only fired when the underlying {@link dev.rafex.etherbrain.ports.model.ModelClient}
     * supports token streaming ({@code supportsStreaming() == true}).
     *
     * <p>Default implementation: no-op — override to forward tokens to the client.
     *
     * @param step  current step number
     * @param token the token text (may be a single character or a short word fragment)
     */
    default void onToken(int step, String token) {}

    /** A no-op listener. Useful as a default / placeholder. */
    static StepListener noop() {
        return new StepListener() {
            @Override public void onStepStart(int step) {}
            @Override public void onToolCall(int step, String t, String a) {}
            @Override public void onToolResult(int step, String t, boolean s, String r) {}
            @Override public void onFinalAnswer(String answer) {}
            @Override public void onError(String error) {}
        };
    }
}
