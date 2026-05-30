package dev.rafex.etherbrain.ports.tools;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;

/**
 * A named, typed capability that the agent can invoke during a conversation.
 *
 * <p>Each tool declares its contract ({@link #name()}, {@link #description()},
 * {@link #inputSchema()}) so the language model can decide when and how to call it.
 * The {@link #execute} method is called by {@link ToolExecutor} when the model
 * requests it.
 *
 * <h2>Declaring the input schema</h2>
 * {@link #inputSchema()} must return a valid JSON Schema object that describes
 * the expected {@code arguments} string passed to {@link #execute}. Example:
 * <pre>{@code
 * @Override
 * public String inputSchema() {
 *     return """
 *         {
 *           "type": "object",
 *           "properties": {
 *             "query": { "type": "string", "description": "Text to search" },
 *             "limit": { "type": "integer", "default": 5 }
 *           },
 *           "required": ["query"]
 *         }
 *         """;
 * }
 * }</pre>
 *
 * <h2>Registering a tool</h2>
 * Tools must be registered in {@link ToolRegistry} and listed in
 * {@link dev.rafex.etherbrain.ports.runtime.AgentConfig#enabledTools()} to be
 * visible to the agent.
 */
public interface Tool {

    /** Unique identifier used by the model to invoke this tool. */
    String name();

    /**
     * Human-readable description for the model.
     * Explain clearly <em>what</em> this tool does and <em>when</em> to use it.
     */
    String description();

    /**
     * JSON Schema string describing the structure of the {@code arguments} parameter
     * passed to {@link #execute}. Must be a JSON object schema.
     */
    String inputSchema();

    /**
     * Executes the tool with the given arguments and returns a result.
     *
     * @param arguments JSON string conforming to {@link #inputSchema()}
     * @param context   current execution context providing session and config
     * @return a {@link ToolResult} indicating success or failure and the content
     * @throws Exception if execution fails unexpectedly; the caller will catch
     *                   and report it as a tool error
     */
    ToolResult execute(String arguments, ExecutionContext context) throws Exception;
}
