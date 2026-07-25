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
 * Security test for the {@link PermissionScope#SKILL_INVOKE} RBAC gate (#191). {@code skill.invoke} is
 * belt-gated AND scope-gated: belt membership alone must NOT authorize it — the caller's effective scopes
 * (the P2-11 RBAC gate) must also grant {@code SKILL_INVOKE}, or the {@link ToolExecutor} refuses it with
 * {@link PermissionDeniedException} and audits the attempt {@code denied}. Mirrors
 * {@code McpRemoteScopeGateTest} for the skill scope, so a regression dropping the new scope from the gate
 * goes red here.
 */
class SkillInvokeScopeGateTest {

    private static final ToolSpec SKILL_INVOKE =
            new ToolSpec("skill.invoke", "invoke a skill", PermissionScope.SKILL_INVOKE,
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}");

    private static ToolExecutor executor(InMemoryToolInvocationRecorder recorder) {
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        return executor;
    }

    @Test
    void skillInvokeInBeltButWithoutTheScopeIsDeniedAndAuditedDenied() throws Exception {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);
        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("skill.invoke must not run without SKILL_INVOKE");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, Set.of(PermissionScope.FS_READ))
                        .call(() -> executor.execute("sess-1", new AgentId("main"),
                                List.of(SKILL_INVOKE), "skill.invoke", "{\"name\":\"greeting\"}", mustNotRun)));

        assertEquals(1, recorder.invocations().size());
        ToolInvocation audited = recorder.invocations().get(0);
        assertEquals("skill.invoke", audited.toolName());
        assertSame(InvocationStatus.DENIED, audited.status());
        assertNull(audited.result(), "a scope-denied skill call produced no result (the skill was never read)");
    }

    @Test
    void skillInvokeInBeltAndGrantedTheScopeRunsAndIsAuditedOk() throws Exception {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ToolExecutor executor = executor(recorder);

        String result = ScopedValue
                .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES,
                        Set.of(PermissionScope.FS_READ, PermissionScope.SKILL_INVOKE))
                .call(() -> executor.execute("sess-1", new AgentId("main"),
                        List.of(SKILL_INVOKE), "skill.invoke", "{\"name\":\"greeting\"}", () -> "Hello Ada"));

        assertEquals("Hello Ada", result);
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status());
    }
}
