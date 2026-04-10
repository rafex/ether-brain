package dev.rafex.etherbrain.ports.policy;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;

public interface PolicyEngine {

    void checkBeforeStep(ExecutionContext context, int step);

    void checkAfterStep(ExecutionContext context, int step);
}
