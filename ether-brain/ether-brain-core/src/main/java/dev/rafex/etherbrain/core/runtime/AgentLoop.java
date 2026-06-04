package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.common.MessageConstants;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.ports.model.BatchedToolRequest;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.observability.MetricsCollector;
import dev.rafex.etherbrain.ports.policy.PolicyEngine;
import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.runtime.StepListener;
import dev.rafex.etherbrain.ports.tools.ToolCall;
import dev.rafex.etherbrain.ports.tools.ToolExecutor;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import dev.rafex.ether.logging.core.logger.EtherLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The ReAct agent loop: model → tool → model → … → final answer.
 *
 * <h2>Cancellation</h2>
 * If {@code context.cancellationToken()} is set and fires, the loop throws
 * {@code AgentException("Agent loop cancelled")} at the start of the next step.
 *
 * <h2>Retry</h2>
 * When a {@link RetryPolicy} is provided and {@code shouldRetry} returns {@code true},
 * the same tool call is re-executed (without asking the model again) up to the
 * configured maximum. The conversation history records each failure so the model
 * sees a full picture on final failure.
 *
 * <h2>Parallel tool execution (future)</h2>
 * Currently the standard codecs emit one {@link ToolRequest} per turn.
 * When multi-tool-call support is added to codecs, the loop will handle them
 * concurrently via {@link java.util.concurrent.CompletableFuture}.
 */
public final class AgentLoop {

    private final ModelClient        modelClient;
    private final ToolRegistry       toolRegistry;
    private final ToolExecutor       toolExecutor;
    private final PromptBuilder      promptBuilder;
    private final PolicyEngine       policyEngine;
    private final RetryPolicy        retryPolicy;       // null = no retry
    private final StepListener       stepListener;      // null = no progress events
    private final MetricsCollector   metricsCollector;  // null-safe (noop if absent)

    /** Backward-compatible — no retry, no step listener, no metrics. */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine
    ) {
        this(modelClient, toolRegistry, toolExecutor, promptBuilder, policyEngine,
                null, null, MetricsCollector.noop());
    }

    /** With retry, no step listener, no metrics. */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine,
            RetryPolicy retryPolicy
    ) {
        this(modelClient, toolRegistry, toolExecutor, promptBuilder, policyEngine,
                retryPolicy, null, MetricsCollector.noop());
    }

    /** With retry + step listener, no metrics. */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine,
            RetryPolicy retryPolicy,
            StepListener stepListener
    ) {
        this(modelClient, toolRegistry, toolExecutor, promptBuilder, policyEngine,
                retryPolicy, stepListener, MetricsCollector.noop());
    }

    /** Full constructor — retry + step listener + metrics. */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine,
            RetryPolicy retryPolicy,
            StepListener stepListener,
            MetricsCollector metricsCollector
    ) {
        this.modelClient       = modelClient;
        this.toolRegistry      = toolRegistry;
        this.toolExecutor      = toolExecutor;
        this.promptBuilder     = promptBuilder;
        this.policyEngine      = policyEngine;
        this.retryPolicy       = retryPolicy;
        this.stepListener      = stepListener;
        this.metricsCollector  = metricsCollector != null
                ? metricsCollector : MetricsCollector.noop();
    }

    /**
     * Returns a copy of this loop with the given {@link StepListener} attached.
     * Allows the HTTP transport to inject an SSE listener per-request without
     * rebuilding the entire loop.
     */
    public AgentLoop withListener(StepListener listener) {
        return new AgentLoop(modelClient, toolRegistry, toolExecutor,
                promptBuilder, policyEngine, retryPolicy, listener, metricsCollector);
    }

    /**
     * Returns a copy of this loop with the given {@link MetricsCollector} attached.
     * Called by {@code ApplicationBootstrap} after the singleton is built.
     */
    public AgentLoop withMetrics(MetricsCollector metrics) {
        return new AgentLoop(modelClient, toolRegistry, toolExecutor,
                promptBuilder, policyEngine, retryPolicy, stepListener, metrics);
    }

    public String run(ExecutionContext context) throws Exception {
        // Per-tool retry counters — reset on each run() call (thread-safe for parallel batches)
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();

        // Correlation tags shared across all metrics in this run
        String[] runTags = requestTags(context);

        Instant runStart = Instant.now();
        int totalSteps = 0;

        for (int step = 0; step < context.agentConfig().maxSteps(); step++) {
            int currentStep = step + 1;
            totalSteps = currentStep;

            // ── Cancellation check ────────────────────────────────────────────
            if (context.isCancelled()) {
                EtherLog.warn(AgentLoop.class,
                        "Step {} - loop cancelled for session {}",
                        currentStep, context.sessionId());
                metricsCollector.record("agent.run.duration",
                        Duration.between(runStart, Instant.now()),
                        concat(runTags, "status=cancelled"));
                metricsCollector.increment("agent.run.total",
                        concat(runTags, "status=cancelled"));
                throw new AgentException("Agent loop cancelled");
            }

            policyEngine.checkBeforeStep(context, step);
            emit(() -> stepListener.onStepStart(currentStep));

            EtherLog.info(AgentLoop.class,
                    "Step {} - building model request for session {}",
                    currentStep, context.sessionId());

            ModelRequest  request  = promptBuilder.build(context, toolRegistry);
            ModelResponse response = callModel(request, context.agentConfig().modelTimeout(), currentStep);

            // ── Final answer ──────────────────────────────────────────────────
            if (response instanceof FinalAnswer finalAnswer) {
                EtherLog.info(AgentLoop.class,
                        "Step {} - final answer generated for session {}",
                        currentStep, context.sessionId());
                context.conversationState().add(
                        new Message(Message.Role.ASSISTANT, finalAnswer.content()));
                policyEngine.checkAfterStep(context, step);
                emit(() -> stepListener.onFinalAnswer(finalAnswer.content()));
                metricsCollector.record("agent.run.duration",
                        Duration.between(runStart, Instant.now()),
                        concat(runTags, "status=ok"));
                metricsCollector.increment("agent.run.total",
                        concat(runTags, "status=ok"));
                metricsCollector.gauge("agent.run.steps", totalSteps, runTags);
                return finalAnswer.content();
            }

            // ── Single tool call ──────────────────────────────────────────────
            if (response instanceof ToolRequest toolRequest) {
                String callId = toolRequest.toolCallId() != null
                        ? toolRequest.toolCallId()
                        : UUID.randomUUID().toString();

                EtherLog.info(AgentLoop.class,
                        "Step {} - executing tool {} for session {}",
                        currentStep, toolRequest.toolName(), context.sessionId());

                emit(() -> stepListener.onToolCall(currentStep,
                        toolRequest.toolName(), toolRequest.arguments()));

                context.conversationState().add(new Message(
                        Message.Role.ASSISTANT,
                        toolRequest.toolName() + MessageConstants.TOOL_CALL_SEP + toolRequest.arguments(),
                        callId));

                Instant toolStart = Instant.now();
                ToolResult result = executeWithRetry(toolRequest, callId, context, retryCount);
                Duration toolDuration = Duration.between(toolStart, Instant.now());

                metricsCollector.record("tool.execution.duration", toolDuration,
                        concat(runTags, "tool=" + toolRequest.toolName(),
                                "status=" + (result.success() ? "ok" : "error")));
                metricsCollector.increment("tool.executions.total",
                        concat(runTags, "tool=" + toolRequest.toolName(),
                                "status=" + (result.success() ? "ok" : "error")));

                emit(() -> stepListener.onToolResult(currentStep,
                        toolRequest.toolName(), result.success(), result.content()));

                context.conversationState().add(
                        new Message(Message.Role.TOOL, result.content(), callId));
                policyEngine.checkAfterStep(context, step);
                continue;
            }

            // ── Batched tool calls (parallel execution) ───────────────────────
            if (response instanceof BatchedToolRequest batch) {
                executeBatchedTools(batch, context, retryCount, currentStep);
                policyEngine.checkAfterStep(context, step);
                continue;
            }

            throw new AgentException(
                    "Unsupported model response type: " + response.getClass().getName());
        }

        emit(() -> stepListener.onError("Max steps exceeded without final answer"));
        metricsCollector.record("agent.run.duration",
                Duration.between(runStart, Instant.now()),
                concat(runTags, "status=max_steps_exceeded"));
        metricsCollector.increment("agent.run.total",
                concat(runTags, "status=max_steps_exceeded"));
        throw new AgentException("Max steps exceeded without final answer");
    }

    // ── Batched (parallel) tool execution ────────────────────────────────────

    /**
     * Executes all tool calls in the batch concurrently using virtual threads.
     *
     * <p>Protocol:
     * <ol>
     *   <li>Record all ASSISTANT tool-call messages first (in order) so the
     *       conversation history is well-formed for OpenAI/Anthropic.</li>
     *   <li>Launch all tool executions in parallel via virtual threads.</li>
     *   <li>Collect results in the original order and add TOOL messages.</li>
     * </ol>
     */
    private void executeBatchedTools(BatchedToolRequest batch, ExecutionContext context,
                                     Map<String, Integer> retryCount,
                                     int currentStep) throws Exception {
        EtherLog.info(AgentLoop.class,
                "Step {} - executing {} tool calls in parallel for session {}",
                currentStep, batch.size(), context.sessionId());

        String[] runTags = requestTags(context);

        // Step 1: assign call IDs, emit events, record ASSISTANT messages (ordered)
        List<String> callIds = new ArrayList<>(batch.size());
        for (ToolRequest tr : batch.calls()) {
            String callId = tr.toolCallId() != null ? tr.toolCallId() : UUID.randomUUID().toString();
            callIds.add(callId);
            emit(() -> stepListener.onToolCall(currentStep, tr.toolName(), tr.arguments()));
            context.conversationState().add(new Message(
                    Message.Role.ASSISTANT,
                    tr.toolName() + MessageConstants.TOOL_CALL_SEP + tr.arguments(),
                    callId));
        }

        // Step 2: execute all tool calls in parallel via virtual threads
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ToolResult>> futures = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.calls().size(); i++) {
            final ToolRequest tr     = batch.calls().get(i);
            final String      callId = callIds.get(i);
            futures.add(executor.submit(() -> {
                Instant toolStart = Instant.now();
                ToolResult r = executeWithRetry(tr, callId, context, retryCount);
                metricsCollector.record("tool.execution.duration",
                        Duration.between(toolStart, Instant.now()),
                        concat(runTags, "tool=" + tr.toolName(),
                                "status=" + (r.success() ? "ok" : "error")));
                metricsCollector.increment("tool.executions.total",
                        concat(runTags, "tool=" + tr.toolName(),
                                "status=" + (r.success() ? "ok" : "error")));
                return r;
            }));
        }
        executor.shutdown();

        // Step 3: collect results in order, emit events, record TOOL messages
        Duration toolTimeout = context.agentConfig().modelTimeout();
        for (int i = 0; i < futures.size(); i++) {
            ToolResult result;
            String toolName = batch.calls().get(i).toolName();
            try {
                result = futures.get(i).get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                result = new ToolResult(toolName, false,
                        "Error: tool timed out after " + toolTimeout.toSeconds() + "s");
                metricsCollector.increment("tool.executions.total",
                        concat(runTags, "tool=" + toolName, "status=timeout"));
            } catch (ExecutionException e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                result = new ToolResult(toolName, false, "Error: " + msg);
                metricsCollector.increment("tool.executions.total",
                        concat(runTags, "tool=" + toolName, "status=error"));
            }
            final ToolResult finalResult = result;
            emit(() -> stepListener.onToolResult(currentStep, toolName,
                    finalResult.success(), finalResult.content()));
            context.conversationState().add(
                    new Message(Message.Role.TOOL, result.content(), callIds.get(i)));
        }
    }

    // ── Retry-aware tool execution ────────────────────────────────────────────

    private ToolResult executeWithRetry(
            ToolRequest toolRequest,
            String callId,
            ExecutionContext context,
            Map<String, Integer> retryCount) throws InterruptedException {

        String toolName  = toolRequest.toolName();
        String arguments = toolRequest.arguments();
        int    attempt   = 0;

        while (true) {
            try {
                return toolExecutor.execute(
                        new ToolCall(toolName, arguments), context);

            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

                // Check if we should retry
                if (retryPolicy != null && retryPolicy.shouldRetry(toolName, attempt, e)) {
                    int totalAttempts = retryCount.merge(toolName, 1, Integer::sum);
                    long delay        = retryPolicy.retryDelayMillis(attempt);

                    EtherLog.warn(AgentLoop.class,
                            "Tool {} failed (attempt {}/total {}), retrying in {}ms — session {}: {}",
                            toolName, attempt + 1, totalAttempts, delay,
                            context.sessionId(), errorMsg);

                    // Record failure in history so model can see it if retries are exhausted
                    context.conversationState().add(new Message(
                            Message.Role.TOOL,
                            "Error (retry " + (attempt + 1) + "): " + errorMsg,
                            callId + "-err-" + attempt));

                    if (delay > 0) Thread.sleep(delay);
                    attempt++;
                    continue;
                }

                // No retry — log and return error result
                EtherLog.warn(AgentLoop.class,
                        "Tool {} failed for session {}: {}",
                        toolName, context.sessionId(), errorMsg);

                return new ToolResult(toolName, false, "Error: " + errorMsg);
            }
        }
    }

    // ── Model call dispatcher ─────────────────────────────────────────────────

    /**
     * Calls the model. Uses streaming when a {@link StepListener} is present AND
     * the model client supports it; falls back to the Future-based blocking call otherwise.
     *
     * <p>In streaming mode the loop timeout is enforced by the HTTP client's own
     * request timeout (configured via {@code LLM_TIMEOUT_SECONDS}), so no extra
     * Future wrapping is needed.
     */
    private ModelResponse callModel(ModelRequest request, Duration timeout,
                                     int currentStep) throws Exception {
        if (stepListener != null && modelClient.supportsStreaming()) {
            try {
                return modelClient.generateStreaming(request,
                        token -> emit(() -> stepListener.onToken(currentStep, token)));
            } catch (Exception e) {
                // Streaming failed — fall through to blocking call
                EtherLog.warn(AgentLoop.class,
                        "Streaming call failed (step {}), falling back to blocking: {}",
                        currentStep, e.getMessage());
            }
        }
        return callWithTimeout(request, timeout);
    }

    // ── StepListener helper ───────────────────────────────────────────────────

    /**
     * Safely invokes a listener callback, swallowing any exception so that a
     * broken listener never aborts an agent run.
     */
    private void emit(Runnable callback) {
        if (stepListener == null) return;
        try {
            callback.run();
        } catch (Exception e) {
            EtherLog.warn(AgentLoop.class, "StepListener threw (ignored): {}", e.getMessage());
        }
    }

    // ── Metrics helpers ───────────────────────────────────────────────────────

    /**
     * Returns the base correlation tags for all metrics in this run:
     * {@code sessionId=<id>} and, if present, {@code requestId=<id>}.
     */
    private static String[] requestTags(ExecutionContext context) {
        String reqId = context.requestId();
        if (reqId != null && !reqId.isBlank()) {
            return new String[]{"sessionId=" + context.sessionId(), "requestId=" + reqId};
        }
        return new String[]{"sessionId=" + context.sessionId()};
    }

    /**
     * Concatenates a base tag array with additional tags.
     * Returns a new array; never mutates the input.
     */
    private static String[] concat(String[] base, String... extra) {
        String[] result = new String[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }

    // ── Timeout-wrapped model call ────────────────────────────────────────────

    private ModelResponse callWithTimeout(ModelRequest request, Duration timeout) throws Exception {
        Callable<ModelResponse> task = () -> modelClient.generate(request);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<ModelResponse> future = executor.submit(task);
        executor.shutdown();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentException("Model call interrupted");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentException("Model call timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Exception ex) throw ex;
            throw new AgentException("Model call failed: " + cause.getMessage());
        }
    }
}
