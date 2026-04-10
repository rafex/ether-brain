package dev.rafex.etherbrain.ports.runtime;

import java.time.Duration;
import java.util.Set;

public record AgentConfig(
        int maxSteps,
        Duration modelTimeout,
        Set<String> enabledTools
) {

    public AgentConfig {
        enabledTools = Set.copyOf(enabledTools);
    }

    public static AgentConfig defaults(Set<String> enabledTools) {
        return new AgentConfig(8, Duration.ofSeconds(30), enabledTools);
    }
}
