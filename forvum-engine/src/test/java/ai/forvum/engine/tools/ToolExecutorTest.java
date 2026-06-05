package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.model.ToolInvocation;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    private ToolExecutor executor(InMemoryToolInvocationRecorder recorder) {
        ToolExecutor executor = new ToolExecutor();
        executor.recorder = recorder;
        return executor;
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
}
