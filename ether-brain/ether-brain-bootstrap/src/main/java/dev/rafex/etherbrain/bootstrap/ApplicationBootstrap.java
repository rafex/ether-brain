package dev.rafex.etherbrain.bootstrap;

import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.runtime.AgentLoop;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.ether.logging.core.config.LoggingConfigurator;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import dev.rafex.etherbrain.ports.model.FinalAnswer;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.model.ModelRequest;
import dev.rafex.etherbrain.ports.model.ModelResponse;
import dev.rafex.etherbrain.ports.model.ToolRequest;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.tools.local.CurrentTimeTool;
import dev.rafex.etherbrain.tools.local.EchoTool;
import dev.rafex.etherbrain.tools.local.InMemoryToolRegistry;
import java.util.Set;
import java.util.logging.Level;

public final class ApplicationBootstrap {

    public AgentRuntime bootstrapForDemo() {
        LoggingConfigurator.configureRootLogger(Level.INFO);

        InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry()
                .register(new EchoTool())
                .register(new CurrentTimeTool());

        AgentConfig agentConfig = AgentConfig.defaults(Set.of("echo", "current_time"));

        AgentLoop agentLoop = new AgentLoop(
                new DemoModelClient(),
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        return new AgentRuntime(new InMemorySessionStore(), agentLoop, agentConfig);
    }

    private static final class DemoModelClient implements ModelClient {

        @Override
        public ModelResponse generate(ModelRequest request) {
            Message latest = request.messages().getLast();

            if (latest.role() == Message.Role.TOOL) {
                return new FinalAnswer("Resultado de tool: " + latest.content());
            }

            String content = latest.content().toLowerCase();
            if (content.contains("time") || content.contains("hora")) {
                return new ToolRequest("current_time", "{}");
            }

            return new ToolRequest("echo", latest.content());
        }
    }
}
