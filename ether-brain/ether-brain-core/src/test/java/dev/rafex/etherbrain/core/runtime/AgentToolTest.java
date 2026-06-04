package dev.rafex.etherbrain.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rafex.etherbrain.ports.runtime.AgentConfig;
import dev.rafex.etherbrain.ports.runtime.AgentRunner;
import dev.rafex.etherbrain.ports.runtime.CancellationToken;
import dev.rafex.etherbrain.ports.runtime.ExecutionContext;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.tools.ToolResult;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentToolTest {

    @Test
    void nameMatchesRunnerName() {
        AgentTool tool = new AgentTool(runner("sub", "desc"));
        assertEquals("sub", tool.name());
    }

    @Test
    void descriptionMatchesRunnerDescription() {
        AgentTool tool = new AgentTool(runner("sub", "A specialised agent."));
        assertEquals("A specialised agent.", tool.description());
    }

    @Test
    void customDescriptionOverridesRunnerDescription() {
        AgentTool tool = new AgentTool(runner("sub", "original"), "custom description");
        assertEquals("custom description", tool.description());
    }

    @Test
    void executesDelegatesMessageToRunner() throws Exception {
        AgentRunner r = new CapturingRunner("sub");
        AgentTool tool = new AgentTool(r);

        ToolResult result = tool.execute("{\"message\":\"hello from orchestrator\"}", context());

        assertTrue(result.success());
        assertEquals("echo:hello from orchestrator", result.content());
    }

    @Test
    void returnsErrorWhenMessageMissing() throws Exception {
        AgentTool tool = new AgentTool(runner("sub", "d"));
        ToolResult result = tool.execute("{}", context());
        assertFalse(result.success());
        assertTrue(result.content().contains("'message' argument is required"));
    }

    @Test
    void returnsErrorForNullArguments() throws Exception {
        AgentTool tool = new AgentTool(runner("sub", "d"));
        ToolResult result = tool.execute(null, context());
        assertFalse(result.success());
    }

    @Test
    void subSessionDefaultsToParentPlusAgentName() throws Exception {
        CapturingRunner r = new CapturingRunner("child");
        AgentTool tool = new AgentTool(r);

        tool.execute("{\"message\":\"hi\"}", context("parent-session"));

        assertEquals("parent-session:child", r.lastSessionId);
    }

    @Test
    void customSessionIdIsRespected() throws Exception {
        CapturingRunner r = new CapturingRunner("child");
        AgentTool tool = new AgentTool(r);

        tool.execute("{\"message\":\"hi\",\"session_id\":\"custom-123\"}", context("parent"));

        assertEquals("custom-123", r.lastSessionId);
    }

    @Test
    void wrapsRunnerExceptionAsFailedResult() throws Exception {
        AgentRunner failing = new AgentRunner() {
            @Override public String agentName()        { return "bomb"; }
            @Override public String agentDescription() { return "d"; }
            @Override public String run(String s, String m) throws Exception {
                throw new RuntimeException("kaboom");
            }
        };
        AgentTool tool = new AgentTool(failing);
        ToolResult result = tool.execute("{\"message\":\"trigger\"}", context());
        assertFalse(result.success());
        assertTrue(result.content().contains("kaboom"));
    }

    // ── extractField ──────────────────────────────────────────────────────────

    @Test
    void extractsFieldFromJson() {
        assertEquals("hello", AgentTool.extractField("{\"message\":\"hello\"}", "message"));
    }

    @Test
    void extractsEscapedChars() {
        String json = "{\"message\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", AgentTool.extractField(json, "message"));
    }

    @Test
    void returnsNullForMissingField() {
        assertFalse(AgentTool.extractField("{\"other\":\"x\"}", "message") != null
                && !AgentTool.extractField("{\"other\":\"x\"}", "message").isBlank());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentRunner runner(String name, String desc) {
        return new AgentRunner() {
            @Override public String agentName()        { return name; }
            @Override public String agentDescription() { return desc; }
            @Override public String run(String s, String m) { return "ok"; }
        };
    }

    private static ExecutionContext context() { return context("test-session"); }

    private static ExecutionContext context(String sessionId) {
        return new ExecutionContext(sessionId, new ConversationState(),
                new AgentConfig(4, Duration.ofSeconds(5), Set.of()));
    }

    private static final class CapturingRunner implements AgentRunner {
        private final String name;
        String lastSessionId;

        CapturingRunner(String name) { this.name = name; }

        @Override public String agentName()        { return name; }
        @Override public String agentDescription() { return "captures"; }

        @Override
        public String run(String sessionId, String message) {
            this.lastSessionId = sessionId;
            return "echo:" + message;
        }

        @Override
        public String run(String sessionId, String message, CancellationToken token) {
            this.lastSessionId = sessionId;
            return "echo:" + message;
        }
    }
}
