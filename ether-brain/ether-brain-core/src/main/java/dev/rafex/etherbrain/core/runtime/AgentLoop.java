package dev.rafex.etherbrain.core.runtime;

import dev.rafex.etherbrain.common.AgentException;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.policy.PolicyEngine;
import dev.rafex.etherbrain.ports.policy.RetryPolicy;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.ToolCall;
import dev.rafex.etherbrain.ports.tools.ToolExecutor;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import dev.rafex.ether.logging.core.logger.EtherLog;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

    private final ModelClient   modelClient;
    private final ToolRegistry  toolRegistry;
    private final ToolExecutor  toolExecutor;
    private final PromptBuilder promptBuilder;
    private final PolicyEngine  policyEngine;
    private final RetryPolicy   retryPolicy;   // null = no retry

    /** Constructor without retry policy (backward-compatible). */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine
    ) {
        this(modelClient, toolRegistry, toolExecutor, promptBuilder, policyEngine, null);
    }

    /** Constructor with optional retry policy. */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine,
            RetryPolicy retryPolicy
    ) {
        this.modelClient   = modelClient;
        this.toolRegistry  = toolRegistry;
        this.toolExecutor  = toolExecutor;
        this.promptBuilder = promptBuilder;
        this.policyEngine  = policyEngine;
        this.retryPolicy   = retryPolicy;
    }

    public String run(ExecutionContext context) throws Exception {
        // Per-tool retry counters — reset on each run() call
        Map<String, Integer> retryCount = new HashMap<>();

        for (int step = 0; step < context.agentConfig().maxSteps(); step++) {
            int currentStep = step + 1;

            // ── Cancellation check ────────────────────────────────────────────
            if (context.isCancelled()) {
                EtherLog.warn(AgentLoop.class,
                        "Step {} - loop cancelled for session {}",
                        currentStep, context.sessionId());
                throw new AgentException("Agent loop cancelled");
            }

            policyEngine.checkBeforeStep(context, step);

            EtherLog.info(AgentLoop.class,
                    "Step {} - building model request for session {}",
                    currentStep, context.sessionId());

            ModelRequest  request  = promptBuilder.build(context, toolRegistry);
            ModelResponse response = callWithTimeout(request, context.agentConfig().modelTimeout());

            // ── Final answer ──────────────────────────────────────────────────
            if (response instanceof FinalAnswer finalAnswer) {
                EtherLog.info(AgentLoop.class,
                        "Step {} - final answer generated for session {}",
                        currentStep, context.sessionId());
                context.conversationState().add(
                        new Message(Message.Role.ASSISTANT, finalAnswer.content()));
                policyEngine.checkAfterStep(context, step);
                return finalAnswer.content();
            }

            // ── Tool call ─────────────────────────────────────────────────────
            if (response instanceof ToolRequest toolRequest) {
                String callId = toolRequest.toolCallId() != null
                        ? toolRequest.toolCallId()
                        : UUID.randomUUID().toString();

                EtherLog.info(AgentLoop.class,
                        "Step {} - executing tool {} for session {}",
                        currentStep, toolRequest.toolName(), context.sessionId());

                context.conversationState().add(new Message(
                        Message.Role.ASSISTANT,
                        toolRequest.toolName() + "|" + toolRequest.arguments(),
                        callId));

                ToolResult result = executeWithRetry(toolRequest, callId, context, retryCount);

                context.conversationState().add(
                        new Message(Message.Role.TOOL, result.content(), callId));
                policyEngine.checkAfterStep(context, step);
                continue;
            }

            throw new AgentException(
                    "Unsupported model response type: " + response.getClass().getName());
        }

        throw new AgentException("Max steps exceeded without final answer");
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
