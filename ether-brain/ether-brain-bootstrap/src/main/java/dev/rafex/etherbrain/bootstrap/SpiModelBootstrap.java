package dev.rafex.etherbrain.bootstrap;

import dev.rafex.etherbrain.core.policy.DefaultPolicyEngine;
import dev.rafex.etherbrain.core.prompt.PromptBuilder;
import dev.rafex.etherbrain.core.runtime.AgentLoop;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.core.tools.DefaultToolExecutor;
import dev.rafex.etherbrain.infra.memory.InMemorySessionStore;
import dev.rafex.etherbrain.ports.model.ModelClient;
import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.spi.model.ModelClientFactory;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import dev.rafex.etherbrain.tools.local.CurrentTimeTool;
import dev.rafex.etherbrain.tools.local.EchoTool;
import dev.rafex.etherbrain.tools.local.InMemoryToolRegistry;
import dev.rafex.ether.logging.core.config.LoggingConfigurator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;

/**
 * Bootstrap that discovers {@link ModelClient} implementations via
 * {@link java.util.ServiceLoader} SPI.
 * <p>
 * Providers are discovered from the classpath and can be selected by name.
 */
public final class SpiModelBootstrap {

    private SpiModelBootstrap() {
    }

    /**
     * Lists all available model providers found on the classpath via SPI.
     */
    public static List<ProviderMetadata> listProviders() {
        List<ProviderMetadata> providers = new ArrayList<>();
        for (ModelClientFactory factory : ServiceLoader.load(ModelClientFactory.class)) {
            providers.add(factory.metadata());
        }
        return providers;
    }

    /**
     * Bootstraps the agent runtime with the named provider.
     *
     * @param providerName name of the provider (e.g., "demo", "openai", "ollama")
     * @param config       provider-specific configuration (API keys, URLs, etc.)
     * @return a fully wired {@link AgentRuntime}
     * @throws IllegalArgumentException if no provider matches the given name
     */
    public static AgentRuntime bootstrap(String providerName, Map<String, String> config) {
        LoggingConfigurator.configureRootLogger(Level.INFO);

        ModelClient modelClient = createModelClient(providerName, config);

        InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry()
                .register(new EchoTool())
                .register(new CurrentTimeTool());

        AgentConfig agentConfig = AgentConfig.defaults(Set.of("echo", "current_time"));

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new PromptBuilder(),
                new DefaultPolicyEngine()
        );

        return new AgentRuntime(new InMemorySessionStore(), agentLoop, agentConfig);
    }

    private static ModelClient createModelClient(String providerName, Map<String, String> config) {
        for (ModelClientFactory factory : ServiceLoader.load(ModelClientFactory.class)) {
            if (factory.name().equals(providerName)) {
                Map<String, String> resolvedConfig = new java.util.LinkedHashMap<>(factory.metadata().defaultConfig());
                resolvedConfig.putAll(config);
                return factory.create(resolvedConfig);
            }
        }
        throw new IllegalArgumentException(
                "No model provider found for name: " + providerName
                        + ". Available: " + listProviders().stream()
                        .map(ProviderMetadata::name)
                        .toList()
        );
    }
}
