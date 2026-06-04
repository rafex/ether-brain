package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.ports.model.BatchedToolRequest;
import java.util.function.Consumer;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.runtime.StepListener;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.tools.Tool;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    // ── Existing tests ────────────────────────────────────────────────────────

    @Test
    void executesToolThenReturnsFinalAnswer() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "What time is it?"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = loop(new FakeModelClient(), toolRegistry);

        String answer = loop.run(ctx("session-1", state, Set.of("echo")));

        assertEquals("Tool said: pong", answer);
    }

    @Test
    void continuesLoopWhenToolErrors() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "Run failing tool then answer"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = loop(new FakeModelClientWithRecovery(), toolRegistry);

        String answer = loop.run(ctx("session-recovery", state, Set.of("echo")));

        assertEquals("Recovered after tool error", answer);
    }

    @Test
    void exhaustsMaxStepsWhenToolKeepsFailing() {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "Run missing"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of());
        AgentLoop loop = loop(request -> new ToolRequest("missing", "{}"), toolRegistry);

        AgentException ex = assertThrows(AgentException.class, () -> loop.run(
                ctx("session-missing", state, Set.of("missing"))));
        assertTrue(ex.getMessage().contains("Max steps exceeded"));
    }

    @Test
    void trimsMessageWindowDuringExecution() throws Exception {
        ConversationState state = new ConversationState(6);
        state.add(new Message(Message.Role.USER, "trim test"));

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = loop(new FakeModelClient(), toolRegistry);

        loop.run(ctx("session-trim", state, Set.of("echo")));

        assertTrue(state.size() <= 6, "Expected <= 6 messages, got " + state.size());
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    void loopStopsWhenTokenCancelledBeforeFirstStep() {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "cancel me"));

        CancellationToken.Mutable token = CancellationToken.create();
        token.cancel(); // cancelled BEFORE run

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(new EchoTool()));
        AgentLoop loop = loop(new FakeModelClient(), toolRegistry);

        AgentException ex = assertThrows(AgentException.class, () ->
                loop.run(ctxWithToken("session-cancel", state, Set.of("echo"), token)));
        assertTrue(ex.getMessage().contains("cancelled"),
                "Expected 'cancelled' in message, got: " + ex.getMessage());
    }

    @Test
    void loopStopsWhenTokenCancelledBetweenSteps() throws InterruptedException {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "cancel mid-loop"));

        CancellationToken.Mutable token = CancellationToken.create();
        AtomicInteger toolCallCount = new AtomicInteger();

        // A model that always requests a tool (so the loop keeps running)
        // After the first tool call we cancel the token
        Tool slowTool = new Tool() {
            @Override public String name()        { return "slow"; }
            @Override public String description() { return "d"; }
            @Override public String inputSchema() { return "{}"; }
            @Override
            public ToolResult execute(String args, ExecutionContext ctx) {
                if (toolCallCount.incrementAndGet() >= 1) token.cancel();
                return new ToolResult(name(), true, "done");
            }
        };

        ToolRegistry toolRegistry = new TestToolRegistry(List.of(slowTool));
        AgentLoop loop = loop(request -> {
            boolean hasTool = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL);
            return hasTool ? new ToolRequest("slow", "{}") : new ToolRequest("slow", "{}");
        }, toolRegistry);

        // Loop should be cancelled after the first tool call triggers the token
        assertThrows(AgentException.class, () ->
                loop.run(ctxWithToken("session-cancel-mid", state, Set.of("slow"), token)));
        assertTrue(toolCallCount.get() >= 1, "Tool should have been called at least once");
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Test
    void retriesFailingToolUpToMax() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "retry me"));

        AtomicInteger attempts = new AtomicInteger();

        // Tool fails on the first 2 attempts, succeeds on the 3rd
        Tool flakyTool = new Tool() {
            @Override public String name()        { return "flaky"; }
            @Override public String description() { return "d"; }
            @Override public String inputSchema() { return "{}"; }
            @Override
            public ToolResult execute(String args, ExecutionContext ctx) throws Exception {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("transient failure " + attempts.get());
                }
                return new ToolResult(name(), true, "success-on-try-" + attempts.get());
            }
        };

        ToolRegistry registry = new TestToolRegistry(List.of(flakyTool));
        RetryPolicy retryPolicy = RetryPolicy.fixed(3, 0); // up to 3 retries, no delay
        AgentLoop loop = loopWithRetry(
                request -> {
                    boolean hasTool = request.messages().stream()
                            .anyMatch(m -> m.role() == Message.Role.TOOL
                                    && m.content().startsWith("success"));
                    return hasTool
                            ? new FinalAnswer("Final: " + "success")
                            : new ToolRequest("flaky", "{}");
                },
                registry,
                retryPolicy);

        String answer = loop.run(ctx("session-retry", state, Set.of("flaky")));
        assertTrue(answer.startsWith("Final:"), "Expected final answer, got: " + answer);
        assertEquals(3, attempts.get(), "Expected exactly 3 attempts (2 failures + 1 success)");
    }

    @Test
    void doesNotRetryWhenPolicyIsNone() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "no retry"));

        AtomicInteger attempts = new AtomicInteger();
        Tool failingTool = new Tool() {
            @Override public String name()        { return "failer"; }
            @Override public String description() { return "d"; }
            @Override public String inputSchema() { return "{}"; }
            @Override
            public ToolResult execute(String args, ExecutionContext ctx) throws Exception {
                attempts.incrementAndGet();
                throw new RuntimeException("always fails");
            }
        };

        ToolRegistry registry = new TestToolRegistry(List.of(failingTool));
        // Model returns tool on first call, then FinalAnswer after seeing the error
        AgentLoop loop = loopWithRetry(
                request -> {
                    boolean sawError = request.messages().stream()
                            .anyMatch(m -> m.role() == Message.Role.TOOL
                                    && m.content().startsWith("Error:"));
                    return sawError ? new FinalAnswer("handled error") : new ToolRequest("failer", "{}");
                },
                registry,
                RetryPolicy.none());

        String answer = loop.run(ctx("session-no-retry", state, Set.of("failer")));
        assertEquals("handled error", answer);
        assertEquals(1, attempts.get(), "Tool must only be called once with no-retry policy");
    }

    // ── Streaming (token-by-token) ────────────────────────────────────────────

    @Test
    void streamingClientEmitsTokensViaListener() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "stream me"));

        // A model client that overrides generateStreaming with real token emission
        ModelClient streamingClient = new ModelClient() {
            @Override
            public ModelResponse generate(ModelRequest req) {
                return new FinalAnswer("streamed response");
            }
            @Override
            public ModelResponse generateStreaming(ModelRequest req,
                                                    Consumer<String> onToken) throws Exception {
                onToken.accept("streamed ");
                onToken.accept("response");
                return new FinalAnswer("streamed response");
            }
            @Override public boolean supportsStreaming() { return true; }
        };

        List<String> tokens = new java.util.ArrayList<>();
        StepListener listener = new StepListener() {
            @Override public void onStepStart(int s)                                  {}
            @Override public void onToolCall(int s, String t, String a)               {}
            @Override public void onToolResult(int s, String t, boolean ok, String r) {}
            @Override public void onFinalAnswer(String a)                              {}
            @Override public void onError(String e)                                    {}
            @Override public void onToken(int step, String token) { tokens.add(token); }
        };

        ToolRegistry registry = new TestToolRegistry(List.of());
        AgentLoop loop = new AgentLoop(streamingClient, registry,
                new DefaultToolExecutor(registry), new PromptBuilder(),
                new DefaultPolicyEngine(), null, listener);

        String answer = loop.run(ctx("session-stream", state, Set.of()));

        assertEquals("streamed response", answer);
        assertEquals(List.of("streamed ", "response"), tokens,
                "Expected exactly 2 tokens in order");
    }

    @Test
    void nonStreamingClientDoesNotCallOnToken() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "no stream"));

        // Default ModelClient — supportsStreaming() returns false
        ModelClient nonStreaming = request -> new FinalAnswer("blocking response");

        List<String> tokens = new java.util.ArrayList<>();
        StepListener listener = new StepListener() {
            @Override public void onStepStart(int s)                                  {}
            @Override public void onToolCall(int s, String t, String a)               {}
            @Override public void onToolResult(int s, String t, boolean ok, String r) {}
            @Override public void onFinalAnswer(String a)                              {}
            @Override public void onError(String e)                                    {}
            @Override public void onToken(int step, String token) { tokens.add(token); }
        };

        ToolRegistry registry = new TestToolRegistry(List.of());
        AgentLoop loop = new AgentLoop(nonStreaming, registry,
                new DefaultToolExecutor(registry), new PromptBuilder(),
                new DefaultPolicyEngine(), null, listener);

        String answer = loop.run(ctx("session-nostream", state, Set.of()));

        assertEquals("blocking response", answer);
        assertTrue(tokens.isEmpty(), "Non-streaming client must not emit tokens via onToken");
    }

    // ── StepListener (progress events) ───────────────────────────────────────

    @Test
    void stepListenerReceivesAllEvents() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "test listener"));

        ToolRegistry registry = new TestToolRegistry(List.of(new EchoTool()));

        List<String> events = new java.util.ArrayList<>();
        StepListener listener = new StepListener() {
            @Override public void onStepStart(int step)  { events.add("start:" + step); }
            @Override public void onToolCall(int s, String t, String a) { events.add("call:" + t); }
            @Override public void onToolResult(int s, String t, boolean ok, String r) { events.add("result:" + t + ":" + ok); }
            @Override public void onFinalAnswer(String a) { events.add("answer"); }
            @Override public void onError(String e)       { events.add("error"); }
        };

        AgentLoop loop = new AgentLoop(new FakeModelClient(), registry,
                new DefaultToolExecutor(registry), new PromptBuilder(),
                new DefaultPolicyEngine(), null, listener);

        loop.run(ctx("session-listener", state, Set.of("echo")));

        assertTrue(events.contains("start:1"),         "must emit onStepStart");
        assertTrue(events.contains("call:echo"),        "must emit onToolCall");
        assertTrue(events.contains("result:echo:true"), "must emit onToolResult");
        assertTrue(events.contains("answer"),           "must emit onFinalAnswer");
    }

    @Test
    void listenerExceptionDoesNotAbortLoop() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "survive listener crash"));

        ToolRegistry registry = new TestToolRegistry(List.of(new EchoTool()));

        // Listener that always throws
        StepListener crashingListener = new StepListener() {
            @Override public void onStepStart(int s)                              { throw new RuntimeException("boom"); }
            @Override public void onToolCall(int s, String t, String a)          { throw new RuntimeException("boom"); }
            @Override public void onToolResult(int s, String t, boolean ok, String r) { throw new RuntimeException("boom"); }
            @Override public void onFinalAnswer(String a)                         { throw new RuntimeException("boom"); }
            @Override public void onError(String e)                               { throw new RuntimeException("boom"); }
        };

        AgentLoop loop = new AgentLoop(new FakeModelClient(), registry,
                new DefaultToolExecutor(registry), new PromptBuilder(),
                new DefaultPolicyEngine(), null, crashingListener);

        // Must complete normally despite the listener throwing
        String answer = loop.run(ctx("session-crashlistener", state, Set.of("echo")));
        assertEquals("Tool said: pong", answer);
    }

    // ── Parallel (batched) tool execution ─────────────────────────────────────

    @Test
    void executesBatchedToolCallsAndReturnsFinalAnswer() throws Exception {
        ConversationState state = new ConversationState();
        state.add(new Message(Message.Role.USER, "run both tools"));

        AtomicInteger callCount = new AtomicInteger();
        Tool counter = new Tool() {
            @Override public String name()        { return "counter"; }
            @Override public String description() { return "d"; }
            @Override public String inputSchema() { return "{}"; }
            @Override
            public ToolResult execute(String args, ExecutionContext ctx) {
                callCount.incrementAndGet();
                return new ToolResult(name(), true, "result-" + args);
            }
        };

        ToolRegistry registry = new TestToolRegistry(List.of(counter));

        // Step 1: model returns a batch of 2 calls
        // Step 2: model sees both TOOL messages and returns FinalAnswer
        AtomicInteger modelCallCount = new AtomicInteger();
        AgentLoop loop = loop(request -> {
            int mc = modelCallCount.incrementAndGet();
            if (mc == 1) {
                // First model call → return 2 tool calls in a batch
                return new BatchedToolRequest(List.of(
                        new ToolRequest("id-a", "counter", "\"a\""),
                        new ToolRequest("id-b", "counter", "\"b\"")));
            }
            // Second model call → check that both tool results arrived
            long toolResults = request.messages().stream()
                    .filter(m -> m.role() == Message.Role.TOOL).count();
            return new FinalAnswer("done:tools=" + toolResults);
        }, registry);

        String answer = loop.run(ctx("session-batch", state, Set.of("counter")));

        assertEquals(2, callCount.get(), "Both tool calls must be executed");
        assertTrue(answer.startsWith("done:tools=2"),
                "Expected 2 tool results in history, got: " + answer);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentLoop loop(ModelClient client, ToolRegistry registry) {
        return new AgentLoop(client, registry, new DefaultToolExecutor(registry),
                new PromptBuilder(), new DefaultPolicyEngine());
    }

    private static AgentLoop loopWithRetry(ModelClient client, ToolRegistry registry,
                                            RetryPolicy policy) {
        return new AgentLoop(client, registry, new DefaultToolExecutor(registry),
                new PromptBuilder(), new DefaultPolicyEngine(), policy);
    }

    private static ExecutionContext ctx(String sessionId, ConversationState state, Set<String> tools) {
        return new ExecutionContext(sessionId, state,
                new AgentConfig(8, Duration.ofSeconds(5), tools));
    }

    private static ExecutionContext ctxWithToken(String sessionId, ConversationState state,
                                                  Set<String> tools, CancellationToken token) {
        return new ExecutionContext(sessionId, state,
                new AgentConfig(8, Duration.ofSeconds(5), tools), null, token);
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private static final class FakeModelClient implements ModelClient {
        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL);
            return hasToolResult ? new FinalAnswer("Tool said: pong") : new ToolRequest("echo", "pong");
        }
    }

    private static final class FakeModelClientWithRecovery implements ModelClient {
        @Override
        public ModelResponse generate(ModelRequest request) {
            boolean hasError = request.messages().stream()
                    .anyMatch(m -> m.role() == Message.Role.TOOL && m.content().startsWith("Error:"));
            return hasError ? new FinalAnswer("Recovered after tool error")
                    : new ToolRequest("missing_tool", "{}");
        }
    }

    private static final class EchoTool implements Tool {
        @Override public String name()        { return "echo"; }
        @Override public String description() { return "Returns the provided argument"; }
        @Override public String inputSchema() { return "{\"type\":\"string\"}"; }
        @Override
        public ToolResult execute(String arguments, ExecutionContext context) {
            return new ToolResult(name(), true, arguments);
        }
    }

    private record TestToolRegistry(Collection<Tool> tools) implements ToolRegistry {
        @Override
        public Optional<Tool> find(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
        @Override public Collection<Tool> all() { return tools; }
    }
}
