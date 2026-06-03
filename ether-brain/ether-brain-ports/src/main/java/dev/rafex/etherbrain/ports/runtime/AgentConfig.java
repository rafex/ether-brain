package dev.rafex.etherbrain.ports.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record AgentConfig(
        int maxSteps,
        Duration modelTimeout,
        Set<String> enabledTools,
        Map<String, RemoteServiceConfig> remoteServices,
        String systemPrompt
) {

    /** System prompt por defecto cuando no se configura uno explícito. */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are EtherBrain, a deterministic AI agent. " +
            "Use the available tools when needed to answer the user's request. " +
            "Provide clear and concise answers.";

    public AgentConfig {
        enabledTools   = Set.copyOf(enabledTools);
        remoteServices = Map.copyOf(remoteServices);
        systemPrompt   = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    /** Backward-compatible — sin remote services ni system prompt personalizado. */
    public AgentConfig(int maxSteps, Duration modelTimeout, Set<String> enabledTools) {
        this(maxSteps, modelTimeout, enabledTools, Map.of(), DEFAULT_SYSTEM_PROMPT);
    }

    /** Backward-compatible — sin system prompt personalizado. */
    public AgentConfig(int maxSteps, Duration modelTimeout,
                       Set<String> enabledTools,
                       Map<String, RemoteServiceConfig> remoteServices) {
        this(maxSteps, modelTimeout, enabledTools, remoteServices, DEFAULT_SYSTEM_PROMPT);
    }

    public static AgentConfig defaults(Set<String> enabledTools) {
        return new AgentConfig(8, Duration.ofSeconds(30), enabledTools,
                Map.of(), DEFAULT_SYSTEM_PROMPT);
    }

    public static AgentConfig defaults(Set<String> enabledTools,
                                       Map<String, RemoteServiceConfig> remoteServices) {
        return new AgentConfig(8, Duration.ofSeconds(30), enabledTools,
                remoteServices, DEFAULT_SYSTEM_PROMPT);
    }

    /** Returns the config for a named remote service, if registered. */
    public Optional<RemoteServiceConfig> remoteService(String name) {
        return Optional.ofNullable(remoteServices.get(name));
    }
}
