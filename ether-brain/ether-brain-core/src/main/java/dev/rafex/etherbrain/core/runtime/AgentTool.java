package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.common.JsonUtils;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolResult;

/**
 * Wraps an {@link AgentRunner} as a {@link Tool} so that one agent can
 * delegate tasks to another <em>in-process</em>, without HTTP overhead.
 *
 * <h2>Input schema</h2>
 * <pre>{@code
 * {
 *   "message":    "the task or question to delegate",
 *   "session_id": "(optional) sub-session ID; defaults to parent session + agent name"
 * }
 * }</pre>
 *
 * <h2>Session isolation</h2>
 * Each sub-agent call uses its own session to avoid polluting the parent
 * conversation history. The default sub-session ID is
 * {@code "<parentSession>:<agentName>"}, but callers can override it.
 *
 * <h2>Registration</h2>
 * <pre>{@code
 * AgentRuntime researcher = buildResearcher();   // AgentRuntime implements AgentRunner
 * toolRegistry.register(new AgentTool(researcher));
 * enabledTools.add(researcher.agentName());
 * }</pre>
 */
public final class AgentTool implements Tool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "The task, question, or instruction to delegate to this agent."
                },
                "session_id": {
                  "type": "string",
                  "description": "Optional sub-session ID. A unique ID is generated automatically if omitted."
                }
              },
              "required": ["message"]
            }
            """;

    private final AgentRunner runner;
    private final String      description;

    /**
     * Creates an AgentTool using the runner's own description.
     *
     * @param runner the sub-agent to wrap
     */
    public AgentTool(AgentRunner runner) {
        this(runner, runner.agentDescription());
    }

    /**
     * Creates an AgentTool with a custom description shown to the orchestrating model.
     *
     * @param runner      the sub-agent to wrap
     * @param description description visible to the orchestrating model
     */
    public AgentTool(AgentRunner runner, String description) {
        this.runner      = runner;
        this.description = description;
    }

    @Override
    public String name() { return runner.agentName(); }

    @Override
    public String description() { return description; }

    @Override
    public String inputSchema() { return SCHEMA; }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        String json = arguments == null ? "{}" : arguments;

        String message = JsonUtils.extractField(json, "message");
        if (message == null || message.isBlank()) {
            return new ToolResult(name(), false,
                    "AgentTool '" + name() + "': 'message' argument is required.");
        }

        // Sub-session: isolated from parent, deterministic name
        String subSession = JsonUtils.extractField(json, "session_id");
        if (subSession == null || subSession.isBlank()) {
            subSession = context.sessionId() + ":" + runner.agentName();
        }

        try {
            String answer = runner.run(subSession, message,
                    context.cancellationToken());
            return new ToolResult(name(), true, answer);
        } catch (Exception e) {
            return new ToolResult(name(), false,
                    "Agent '" + name() + "' failed: " + e.getMessage());
        }
    }

    /**
     * Delegates to {@link JsonUtils#extractField} — kept package-private for tests.
     */
    static String extractField(String json, String fieldName) {
        return JsonUtils.extractField(json, fieldName);
    }
}
