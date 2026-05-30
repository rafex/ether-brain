package dev.rafex.etherbrain.ports.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record AgentConfig(
        int maxSteps,
        Duration modelTimeout,
        Set<String> enabledTools,
        Map<String, RemoteServiceConfig> remoteServices
) {

    public AgentConfig {
        enabledTools = Set.copyOf(enabledTools);
        remoteServices = Map.copyOf(remoteServices);
    }

    /** Backward-compatible constructor — no remote services. */
    public AgentConfig(int maxSteps, Duration modelTimeout, Set<String> enabledTools) {
        this(maxSteps, modelTimeout, enabledTools, Map.of());
    }

    public static AgentConfig defaults(Set<String> enabledTools) {
        return new AgentConfig(8, Duration.ofSeconds(30), enabledTools, Map.of());
    }

    public static AgentConfig defaults(Set<String> enabledTools,
                                       Map<String, RemoteServiceConfig> remoteServices) {
        return new AgentConfig(8, Duration.ofSeconds(30), enabledTools, remoteServices);
    }

    /** Returns the config for a named remote service, if registered. */
    public Optional<RemoteServiceConfig> remoteService(String name) {
        return Optional.ofNullable(remoteServices.get(name));
    }
}
