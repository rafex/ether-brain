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
            ModelResponse response = modelClient.generate(request);

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
                EtherLog.info(
                        AgentLoop.class,
                        "Step {} - executing tool {} for session {}",
                        currentStep,
                        toolRequest.toolName(),
                        context.sessionId()
                );
                ToolResult result = toolExecutor.execute(
                        new ToolCall(toolRequest.toolName(), toolRequest.arguments()),
                        context
                );
                context.conversationState().add(new Message(Message.Role.TOOL, renderToolResult(result)));
                policyEngine.checkAfterStep(context, step);
                continue;
            }

            throw new AgentException("Unsupported model response type: " + response.getClass().getName());
        }

        throw new AgentException("Max steps exceeded without final answer");
    }

    private String renderToolResult(ToolResult result) {
        return "tool=%s success=%s content=%s".formatted(
                result.toolName(),
                result.success(),
                result.content()
        );
    }
}
