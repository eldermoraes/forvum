package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.approval.ApprovalGate;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.model.ToolInvocation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Unit tests for {@link ToolExecutor} permission enforcement + audit (ULTRAPLAN section 5.3 / 5.5).
 * The executor enforces the agent's filtered belt before invoking a tool: a tool outside the belt is
 * refused with {@link PermissionDeniedException} and audited {@code denied}; a permitted tool runs and
 * is audited {@code ok}; a throwing tool is audited {@code error} and the failure is rethrown. Uses an
 * in-memory recorder double, so the logic is verifiable without a database.
 */
class ToolExecutorTest {

    private static final ToolSpec READ = new ToolSpec("a.read", "read a thing", PermissionScope.FS_READ, "{}");
    private static final ToolSpec WRITE = new ToolSpec("a.write", "write a thing", PermissionScope.FS_WRITE, "{}");
    private static final ToolSpec CONFIRM =
            new ToolSpec("a.danger", "do a dangerous thing", PermissionScope.FS_WRITE, "{}", true);

    private ToolExecutor executor(InMemoryToolInvocationRecorder recorder) {
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        return executor;
    }

    private ToolExecutor executor(InMemoryToolInvocationRecorder recorder, ApprovalGate gate) {
        ToolExecutor executor = executor(recorder);
        executor.approvals = gate;
        return executor;
    }

    /** Records the single approval request and returns a canned decision. */
    private static final class RecordingGate implements ApprovalGate {
        private final boolean decision;
        private boolean consulted;
        private String toolName;

        RecordingGate(boolean decision) {
            this.decision = decision;
        }

        @Override
        public boolean requireApproval(String sessionId, AgentId agentId, String toolName, String arguments) {
            this.consulted = true;
            this.toolName = toolName;
            return decision;
        }
    }

    @Test
    void confirmRequiredToolRunsWhenApprovedAndIsAuditedOk() {
        // P2-14 #39: a USER_CONFIRM_REQUIRED tool that clears belt + RBAC is parked through the approval
        // gate; an approve lets it run and audits ok (the gate is the THIRD gate, after belt + scope).
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        RecordingGate gate = new RecordingGate(true);

        String result = executor(recorder, gate).execute(
                "sess-1", new AgentId("main"), List.of(CONFIRM), "a.danger", "{}", () -> "did it");

        assertTrue(gate.consulted, "an approve gate must be consulted for a confirm-required tool");
        assertEquals("a.danger", gate.toolName);
        assertEquals("did it", result);
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }

    @Test
    void confirmRequiredToolIsDeniedWithoutRunningWhenRejected() {
        // A reject (or timeout) denies the call without running it and audits denied — the same outcome as
        // a belt/scope miss, surfaced as an ApprovalDeniedException the tool loop renders back to the model.
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        RecordingGate gate = new RecordingGate(false);
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("a rejected confirm-required tool must not run");
        };

        assertThrows(ApprovalDeniedException.class, () -> executor(recorder, gate).execute(
                "sess-1", new AgentId("main"), List.of(CONFIRM), "a.danger", "{}", mustNotRun));

        assertTrue(gate.consulted);
        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertEquals("a.danger", audited.toolName());
        assertSame(InvocationStatus.DENIED, audited.status());
        assertNull(audited.result(), "a rejected call produced no result");
    }

    @Test
    void confirmRequiredToolStillEnforcesBeltAndScopeBeforeTheApprovalGate() throws Exception {
        // The approval gate is LAST: a confirm-required tool outside the caller's effective scopes is
        // denied by the RBAC gate WITHOUT ever consulting the approval gate (no point parking a call the
        // identity may not make at all).
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        RecordingGate gate = new RecordingGate(true);
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("an out-of-scope tool must not run");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, Set.of(PermissionScope.FS_READ))
                        .call(() -> executor(recorder, gate).execute("sess-1", new AgentId("main"),
                                List.of(CONFIRM), "a.danger", "{}", mustNotRun)));

        assertFalse(gate.consulted, "the RBAC gate must deny before the approval gate is consulted");
        assertSame(InvocationStatus.DENIED, recorder.invocations().get(0).status());
    }

    @Test
    void deniedToolIsRefusedWithoutRunningAndAuditedDenied() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("the action must not run for a denied tool");
        };

        assertThrows(PermissionDeniedException.class, () -> executor(recorder).execute(
                "sess-1", new AgentId("main"), List.of(READ), "a.write", "{}", mustNotRun));

        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertEquals("a.write", audited.toolName());
        assertSame(InvocationStatus.DENIED, audited.status());
        assertNull(audited.result(), "a denied call produced no result");
    }

    @Test
    void permittedToolRunsAndIsAuditedOk() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();

        String result = executor(recorder).execute(
                "sess-1", new AgentId("main"), List.of(READ), "a.read", "{}", () -> "the contents");

        assertEquals("the contents", result);
        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertSame(InvocationStatus.OK, audited.status());
        assertEquals("the contents", audited.result());
    }

    @Test
    void throwingToolIsAuditedErrorAndTheFailureIsRethrown() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        Supplier<String> boom = () -> {
            throw new IllegalStateException("disk on fire");
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> executor(recorder)
                .execute("sess-1", new AgentId("main"), List.of(READ), "a.read", "{}", boom));

        assertEquals("disk on fire", thrown.getMessage());
        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertSame(InvocationStatus.ERROR, audited.status());
        assertTrue(audited.result().contains("disk on fire"),
                "the error row captures the failure, got: " + audited.result());
    }

    @Test
    void inBeltButOutOfScopeIsDeniedWithoutRunningAndAuditedDenied() throws Exception {
        // P2-11 RBAC: a.write IS in the belt, but the caller's effective scopes grant only FS_READ — the
        // orthogonal scope gate must deny it (belt membership alone no longer authorizes).
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("an out-of-scope tool must not run");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, Set.of(PermissionScope.FS_READ))
                        .call(() -> executor.execute("sess-1", new AgentId("main"),
                                List.of(READ, WRITE), "a.write", "{}", mustNotRun)));

        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertEquals("a.write", audited.toolName());
        assertSame(InvocationStatus.DENIED, audited.status());
        assertNull(audited.result(), "a scope-denied call produced no result");
    }

    @Test
    void inBeltAndInScopeRunsAndIsAuditedOk() throws Exception {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);

        String result = ScopedValue
                .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES,
                        Set.of(PermissionScope.FS_READ, PermissionScope.FS_WRITE))
                .call(() -> executor.execute("sess-1", new AgentId("main"),
                        List.of(READ, WRITE), "a.write", "{}", () -> "written"));

        assertEquals("written", result);
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }

    @Test
    void unboundEffectiveScopesFallBackToBeltOnlyAuthorization() {
        // A caller outside a turn entry (a lower-level unit test) leaves CURRENT_EFFECTIVE_SCOPES unbound;
        // the belt remains the sole gate — the pre-P2-11 behavior. Production turn entries always bind it.
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();

        String result = executor(recorder).execute("sess-1", new AgentId("main"),
                List.of(READ, WRITE), "a.write", "{}", () -> "written");

        assertEquals("written", result);
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }
}
