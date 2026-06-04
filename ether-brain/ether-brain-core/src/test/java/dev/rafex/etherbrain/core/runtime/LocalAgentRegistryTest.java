package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import org.junit.jupiter.api.Test;

class LocalAgentRegistryTest {

    @Test
    void registeredAgentIsFoundByName() {
        LocalAgentRegistry registry = new LocalAgentRegistry();
        registry.register(stub("researcher"));
        assertTrue(registry.get("researcher").isPresent());
    }

    @Test
    void unknownAgentReturnsEmpty() {
        LocalAgentRegistry registry = new LocalAgentRegistry();
        assertFalse(registry.get("ghost").isPresent());
    }

    @Test
    void registrationReplacesExistingName() {
        LocalAgentRegistry registry = new LocalAgentRegistry();
        AgentRunner first  = stub("agent");
        AgentRunner second = stub("agent");
        registry.register(first);
        registry.register(second);
        // second registration must win
        assertEquals(second, registry.get("agent").orElseThrow());
    }

    @Test
    void allReturnsAllRegistered() {
        LocalAgentRegistry registry = new LocalAgentRegistry();
        registry.register(stub("a"));
        registry.register(stub("b"));
        registry.register(stub("c"));
        assertEquals(3, registry.all().size());
    }

    @Test
    void sizeReflectsRegistrations() {
        LocalAgentRegistry registry = new LocalAgentRegistry();
        assertEquals(0, registry.size());
        registry.register(stub("x"));
        assertEquals(1, registry.size());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static AgentRunner stub(String name) {
        return new AgentRunner() {
            @Override public String agentName()        { return name; }
            @Override public String agentDescription() { return "stub " + name; }
            @Override public String run(String s, String m) { return "ok"; }
        };
    }
}
