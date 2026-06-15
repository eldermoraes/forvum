package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.model.ToolInvocation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Security test for the {@link PermissionScope#MCP_REMOTE} second gate (P2-13 / DR-6b §9.3). A remote MCP
 * tool ({@code mcp.<server>.<tool>}) is UNTRUSTED, so the bridge stamps it {@code MCP_REMOTE}; belt
 * membership alone must NOT authorize it — the caller's effective scopes (the P2-11 RBAC gate) must also
 * grant {@code MCP_REMOTE}, or the {@link ToolExecutor} refuses it with {@link PermissionDeniedException}
 * and audits the attempt {@code denied}. Mirrors the FS_WRITE RBAC tests in {@link ToolExecutorTest}, but
 * for the MCP scope, so a regression that dropped the new scope from the gate goes red here.
 */
class McpRemoteScopeGateTest {

    private static final ToolSpec MCP_FORECAST =
            new ToolSpec("mcp.weather.forecast", "remote forecast", PermissionScope.MCP_REMOTE, "{}");

    private static ToolExecutor executor(InMemoryToolInvocationRecorder recorder) {
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        return executor;
    }

    @Test
    void mcpToolInBeltButWithoutMcpRemoteScopeIsDeniedAndAuditedDenied() throws Exception {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("a remote MCP tool must not run without MCP_REMOTE");
        };

        // The tool IS in the belt, but the caller's effective scopes grant only FS_READ — no MCP_REMOTE.
        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, Set.of(PermissionScope.FS_READ))
                        .call(() -> executor.execute("sess-1", new AgentId("main"),
                                List.of(MCP_FORECAST), "mcp.weather.forecast", "{}", mustNotRun)));

        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertEquals("mcp.weather.forecast", audited.toolName());
        assertSame(InvocationStatus.DENIED, audited.status());
        assertNull(audited.result(), "a scope-denied remote call produced no result");
    }

    @Test
    void mcpToolInBeltAndGrantedMcpRemoteScopeRunsAndIsAuditedOk() throws Exception {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);

        String result = ScopedValue
                .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES,
                        Set.of(PermissionScope.FS_READ, PermissionScope.MCP_REMOTE))
                .call(() -> executor.execute("sess-1", new AgentId("main"),
                        List.of(MCP_FORECAST), "mcp.weather.forecast", "{}", () -> "sunny"));

        assertEquals("sunny", result);
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }
}
