package dev.rafex.etherbrain.ports.model;

import java.util.List;

/**
 * A model response that contains multiple tool calls to be executed
 * simultaneously in a single turn.
 *
 * <p>Both the OpenAI API and the Anthropic Messages API can return several tool
 * calls in one response. When they do, {@link BatchedToolRequest} is emitted
 * instead of a single {@link ToolRequest}. The {@code AgentLoop} executes all
 * calls in parallel via virtual threads and adds all results to the conversation
 * before asking the model again.
 *
 * <h2>Single-call equivalence</h2>
 * A batch with exactly one call is semantically equivalent to a plain
 * {@link ToolRequest}. Codecs may emit either form for a single tool call;
 * {@code AgentLoop} handles both.
 *
 * <h2>Conversation history</h2>
 * <pre>
 * ASSISTANT: tool_a | {...}   (call-id-1)
 * ASSISTANT: tool_b | {...}   (call-id-2)
 * TOOL:      result of a      (call-id-1)
 * TOOL:      result of b      (call-id-2)
 * </pre>
 * All ASSISTANT entries are recorded before any TOOL results, matching the
 * ordering expected by OpenAI-compatible APIs.
 */
public record BatchedToolRequest(List<ToolRequest> calls) implements ModelResponse {

    /** Compact constructor — defensive copy to guarantee immutability. */
    public BatchedToolRequest {
        calls = List.copyOf(calls);
    }

    /** Convenience factory for a single-call batch. */
    public static BatchedToolRequest of(ToolRequest single) {
        return new BatchedToolRequest(List.of(single));
    }

    /** Convenience factory for two calls. */
    public static BatchedToolRequest of(ToolRequest first, ToolRequest second) {
        return new BatchedToolRequest(List.of(first, second));
    }

    /** Number of tool calls in this batch. */
    public int size() { return calls.size(); }
}
