package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Execution contract for {@link ShellToolProvider#invoke(String, Map)} (M18 Option A): the provider
 * self-dispatches {@code shell.exec}, reads the allowlist on demand, validates the argv, confines the
 * working directory, and launches the process — with no reflection. The engine's {@code ToolExecutor}
 * gates belt + RBAC + #39 approval and audits; this test exercises the in-provider dispatch + the real
 * process launch against a seeded allowlist + {@code @TempDir} workspace.
 */
class ShellToolProviderTest {

    private static String binaryOrSkip(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        assumeTrue(false, "no POSIX binary among " + List.of(candidates));
        return null; // unreachable
    }

    /** A provider wired to an explicit allowlist file + workspace root (both under {@code @TempDir}). */
    private static ShellToolProvider providerFor(Path home, Path workspace) {
        ShellToolProvider provider = new ShellToolProvider();
        provider.allowlist = new ShellAllowlist(home.resolve("tools").resolve("shell.json"));
        provider.workspace = new WorkspaceRoot(workspace);
        return provider;
    }

    private static Map<String, Object> argvArgs(Object argv, String workingDir) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("argv", argv);
        if (workingDir != null) {
            map.put("workingDir", workingDir);
        }
        return map;
    }

    private static void writeAllowlist(Path home, String json) throws IOException {
        Path file = home.resolve("tools").resolve("shell.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void specIsShellExecRequiringConfirmation() {
        ToolSpec spec = ShellExecTool.SPEC;

        assertEquals("shell.exec", spec.name());
        assertEquals(PermissionScope.SHELL_EXEC, spec.requiredScope());
        assertTrue(spec.userConfirmRequired(),
                "shell.exec is the FIRST tool to require user confirmation (P2-14 #39)");
        assertTrue(spec.parametersJsonSchema().contains("\"type\":\"array\""),
                "argv is declared as a JSON array");
    }

    @Test
    void toolsContributesOnlyShellExec() {
        ShellToolProvider provider = new ShellToolProvider();

        assertEquals(List.of(ShellExecTool.SPEC), provider.tools());
        assertEquals("shell", provider.extensionId());
    }

    @Test
    void invokeRunsAnAllowedCommandAndReturnsOutput(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        String echo = binaryOrSkip("/bin/echo", "/usr/bin/echo");
        writeAllowlist(home, "{\"allowedCommands\":[\"" + echo + "\"]}");
        ShellToolProvider provider = providerFor(home, workspace);

        String out = provider.invoke("shell.exec", argvArgs(List.of(echo, "hi", "there"), null));

        assertEquals("hi there\n", out, "an allowed command runs and its output is returned");
    }

    @Test
    void invokeRefusesAnUnlistedCommand(@TempDir Path home, @TempDir Path workspace) throws IOException {
        writeAllowlist(home, "{\"allowedCommands\":[\"/bin/echo\"]}");
        ShellToolProvider provider = providerFor(home, workspace);

        assertThrows(ShellExecException.class,
                () -> provider.invoke("shell.exec", argvArgs(List.of("/bin/rm", "-rf", "/"), null)),
                "a command outside the allowlist is refused by the in-tool allowlist gate");
    }

    @Test
    void invokeIsFailClosedWithNoAllowlistFile(@TempDir Path home, @TempDir Path workspace) {
        // No shell.json written: fail-closed, every command refused.
        ShellToolProvider provider = providerFor(home, workspace);

        assertThrows(ShellExecException.class,
                () -> provider.invoke("shell.exec", argvArgs(List.of("/bin/echo", "hi"), null)),
                "with no tools/shell.json the tool refuses everything");
    }

    @Test
    void invokeRefusesAWorkingDirOutsideTheWorkspace(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        String echo = binaryOrSkip("/bin/echo", "/usr/bin/echo");
        writeAllowlist(home, "{\"allowedCommands\":[\"" + echo + "\"]}");
        ShellToolProvider provider = providerFor(home, workspace);

        assertThrows(WorkspaceEscapeException.class,
                () -> provider.invoke("shell.exec", argvArgs(List.of(echo, "x"), "../escape")),
                "the workingDir is confined to the workspace root");
    }

    @Test
    void invokeUnknownToolThrows(@TempDir Path home, @TempDir Path workspace) {
        ShellToolProvider provider = providerFor(home, workspace);

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("shell.run", argvArgs(List.of("echo"), null)),
                "a name this provider does not contribute is a programming error");
    }

    @Test
    void invokeRejectsANonArrayArgv(@TempDir Path home, @TempDir Path workspace) {
        ShellToolProvider provider = providerFor(home, workspace);

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("shell.exec", argvArgs("echo hi", null)),
                "argv must be a JSON array, not a string");
    }

    @Test
    void invokeRunsInTheConfiguredWorkingDirectory(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        String pwd = binaryOrSkip("/bin/pwd", "/usr/bin/pwd");
        Path sub = Files.createDirectories(workspace.resolve("sub"));
        writeAllowlist(home, "{\"allowedCommands\":[\"" + pwd + "\"]}");
        ShellToolProvider provider = providerFor(home, workspace);

        String out = provider.invoke("shell.exec", argvArgs(List.of(pwd), "sub"));

        assertEquals(sub.toRealPath().toString(), out.strip(),
                "the model-supplied workingDir is honored, confined to the workspace");
    }
}
