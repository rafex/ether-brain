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
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.tools.ToolCall;
import dev.rafex.etherbrain.ports.tools.ToolExecutor;
import dev.rafex.etherbrain.ports.tools.ToolRegistry;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import dev.rafex.ether.logging.core.logger.EtherLog;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AgentLoop {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final PromptBuilder promptBuilder;
    private final PolicyEngine policyEngine;

    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            PromptBuilder promptBuilder,
            PolicyEngine policyEngine
    ) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.promptBuilder = promptBuilder;
        this.policyEngine = policyEngine;
    }

    public String run(ExecutionContext context) throws Exception {
        for (int step = 0; step < context.agentConfig().maxSteps(); step++) {
            int currentStep = step + 1;
            policyEngine.checkBeforeStep(context, step);
            EtherLog.info(
                    AgentLoop.class,
                    "Step {} - building model request for session {}",
                    currentStep,
                    context.sessionId()
            );
            ModelRequest request = promptBuilder.build(context, toolRegistry);
            ModelResponse response = callWithTimeout(request, context.agentConfig().modelTimeout());

            if (response instanceof FinalAnswer finalAnswer) {
                EtherLog.info(
                        AgentLoop.class,
                        "Step {} - final answer generated for session {}",
                        currentStep,
                        context.sessionId()
                );
                context.conversationState().add(new Message(Message.Role.ASSISTANT, finalAnswer.content()));
                policyEngine.checkAfterStep(context, step);
                return finalAnswer.content();
            }

            if (response instanceof ToolRequest toolRequest) {
                String callId = toolRequest.toolCallId() != null
                        ? toolRequest.toolCallId()
                        : UUID.randomUUID().toString();

                EtherLog.info(
                        AgentLoop.class,
                        "Step {} - executing tool {} for session {}",
                        currentStep,
                        toolRequest.toolName(),
                        context.sessionId()
                );

                context.conversationState().add(new Message(
                        Message.Role.ASSISTANT,
                        toolRequest.toolName() + "|" + toolRequest.arguments(),
                        callId
                ));

                ToolResult result;
                try {
                    result = toolExecutor.execute(
                            new ToolCall(toolRequest.toolName(), toolRequest.arguments()),
                            context
                    );
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    EtherLog.warn(
                            AgentLoop.class,
                            "Step {} - tool {} failed for session {}: {}",
                            currentStep,
                            toolRequest.toolName(),
                            context.sessionId(),
                            errorMsg
                    );
                    context.conversationState().add(
                            new Message(Message.Role.TOOL, "Error: " + errorMsg, callId));
                    policyEngine.checkAfterStep(context, step);
                    continue;
                }

                context.conversationState().add(new Message(Message.Role.TOOL, result.content(), callId));
                policyEngine.checkAfterStep(context, step);
                continue;
            }

            throw new AgentException("Unsupported model response type: " + response.getClass().getName());
        }

        throw new AgentException("Max steps exceeded without final answer");
    }

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
