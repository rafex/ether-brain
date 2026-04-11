package dev.rafex.etherbrain.ports.resources;

import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import java.util.Collection;
import java.util.Optional;

public interface ResourceRegistry {

    Optional<ResourceDescriptor> find(String uri);

    Collection<ResourceDescriptor> all();

    ResourceContent read(String uri, ExecutionContext context) throws Exception;
}
