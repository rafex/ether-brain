package dev.rafex.etherbrain.ports.prompts;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import java.util.Collection;
import java.util.Optional;

public interface PromptRegistry {

    Optional<PromptDescriptor> find(String name);

    Collection<PromptDescriptor> all();

    PromptTemplate get(String name, ExecutionContext context) throws Exception;
}
