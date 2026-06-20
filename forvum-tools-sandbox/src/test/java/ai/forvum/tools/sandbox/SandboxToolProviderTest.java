package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatch contract for {@link SandboxToolProvider#invoke(String, Map)} (M18 Option A): the provider
 * self-dispatches {@code sandbox.run}, reads the config on demand, asserts the default-deny posture, detects
 * the runtime, confines the working directory, and builds the hardened invocation — no reflection. The
 * engine's {@code ToolExecutor} gates belt + RBAC + #39 approval and audits; these tests exercise the
 * in-provider gates that fire BEFORE a container is launched (the actual container run is the live test).
 */
class SandboxToolProviderTest {

    /** A provider wired to an explicit config file + workspace root (both under {@code @TempDir}). */
    private static SandboxToolProvider providerFor(Path home, Path workspace) {
        SandboxToolProvider provider = new SandboxToolProvider();
        provider.config = new SandboxConfig(home.resolve("tools").resolve("sandbox.json"));
        provider.workspace = new WorkspaceRoot(workspace);
        return provider;
    }

    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    private static void writeConfig(Path home, String json) throws IOException {
        Path file = home.resolve("tools").resolve("sandbox.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void specIsSandboxRunRequiringConfirmation() {
        ToolSpec spec = SandboxRunTool.SPEC;

        assertEquals("sandbox.run", spec.name());
        assertEquals(PermissionScope.SHELL_EXEC, spec.requiredScope(),
                "sandbox.run shares shell.exec's SHELL_EXEC scope (the 'sandboxed sibling')");
        assertTrue(spec.userConfirmRequired(),
                "sandbox.run is userConfirmRequired (the merged P2-14 #39 gate)");
        assertTrue(spec.parametersJsonSchema().contains("\"code\"")
                        && spec.parametersJsonSchema().contains("\"argv\""),
                "the schema offers both code and argv");
    }

    @Test
    void toolsContributesOnlySandboxRun() {
        SandboxToolProvider provider = new SandboxToolProvider();

        assertEquals(List.of(SandboxRunTool.SPEC), provider.tools());
        assertEquals("sandbox", provider.extensionId());
    }

    @Test
    void invokeIsFailClosedWithNoConfigFile(@TempDir Path home, @TempDir Path workspace) {
        // No sandbox.json written: fail-closed, every call refused BEFORE any runtime detection.
        SandboxToolProvider provider = providerFor(home, workspace);

        assertThrows(SandboxExecException.class,
                () -> provider.invoke("sandbox.run", args("code", "print(1)")),
                "with no tools/sandbox.json the tool refuses everything (no image)");
    }

    @Test
    void invokeUnknownToolThrows(@TempDir Path home, @TempDir Path workspace) {
        SandboxToolProvider provider = providerFor(home, workspace);

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("sandbox.exec", args("code", "x")),
                "a name this provider does not contribute is a programming error");
    }

    @Test
    void invokeRejectsANonArrayArgv(@TempDir Path home, @TempDir Path workspace) throws IOException {
        writeConfig(home, "{\"image\":\"busybox\"}");
        SandboxToolProvider provider = providerFor(home, workspace);

        // argv must be a JSON array if present; a string is rejected. (This fires after requireRunnable but
        // the runtime is only detected later, so the argv-shape error is what surfaces regardless of host.)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("sandbox.run", args("argv", "echo hi")));
        assertTrue(e.getMessage().contains("argv"), "the argv shape error is reported, got: " + e.getMessage());
    }

    @Test
    void invokeReportsNoRuntimeWhenNeitherIsPinnedNorPresent(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        // Pin a runtime that does not exist so detection is deterministic regardless of the host having
        // podman/docker installed — the no-runtime fail-closed path is what we assert.
        writeConfig(home, "{\"image\":\"busybox\",\"runtime\":\"/no/such/runtime/cri\"}");
        SandboxToolProvider provider = providerFor(home, workspace);

        SandboxExecException e = assertThrows(SandboxExecException.class,
                () -> provider.invoke("sandbox.run", args("code", "echo hi")));
        assertTrue(e.getMessage().contains("no container runtime"),
                "a missing runtime fails closed with a clear message, got: " + e.getMessage());
    }

    @Test
    void invokeRefusesAWorkingDirOutsideTheWorkspace(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        // The workspace-escape filter is lexical and fires before runtime detection's success is needed for
        // a confined path — confine() runs and throws on the traversal regardless of host runtime.
        writeConfig(home, "{\"image\":\"busybox\",\"runtime\":\"/no/such/runtime/cri\"}");
        SandboxToolProvider provider = providerFor(home, workspace);

        assertThrows(WorkspaceEscapeException.class,
                () -> provider.invoke("sandbox.run", args("code", "x", "workingDir", "../escape")),
                "the workingDir is confined to the workspace root");
    }
}
