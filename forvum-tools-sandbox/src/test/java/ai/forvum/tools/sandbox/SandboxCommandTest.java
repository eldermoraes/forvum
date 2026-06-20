package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pure tests for {@link SandboxCommand#build} — the {@code <runtime> run …} argv assembly that carries the
 * whole container-hardening surface (no network, read-only root, dropped caps, non-root user, CPU/mem/pids
 * limits, the confined workspace mount). No process is launched, so the security posture is asserted
 * directly on the argv. This is the {@code ShellExecutorTest} equivalent: the model-controllable inputs
 * (code/argv/workingDir/writable) cannot weaken any of the locked-down flags.
 */
class SandboxCommandTest {

    private static SandboxConfig.Spec spec() {
        return new SandboxConfig.Spec("python:3.12-slim", Optional.empty(), "none", false,
                "1000:1000", "1", "256m", "/workspace", List.of("/bin/sh", "-c"), 60);
    }

    /** The value immediately following the first occurrence of {@code flag} in {@code argv}. */
    private static String valueAfter(List<String> argv, String flag) {
        int i = argv.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < argv.size(), "flag '" + flag + "' present with a value in " + argv);
        return argv.get(i + 1);
    }

    @Test
    void buildsAHardenedCodeRun() {
        List<String> argv = SandboxCommand.build(
                "podman", spec(), Path.of("/home/u/.forvum/workspace"), "print('hi')", null, false);

        assertEquals("podman", argv.get(0));
        assertEquals("run", argv.get(1));
        assertTrue(argv.contains("--rm"), "the container is ephemeral");
        assertEquals("none", valueAfter(argv, "--network"), "no network egress by default");
        assertTrue(argv.contains("--read-only"), "the root filesystem is immutable");
        assertEquals("ALL", valueAfter(argv, "--cap-drop"), "all Linux capabilities are dropped");
        assertEquals("no-new-privileges", valueAfter(argv, "--security-opt"), "no setuid escalation");
        assertEquals("1000:1000", valueAfter(argv, "--user"), "a non-root in-container user");
        assertEquals("1", valueAfter(argv, "--cpus"));
        assertEquals("256m", valueAfter(argv, "--memory"));
        assertEquals(SandboxCommand.PIDS_LIMIT, valueAfter(argv, "--pids-limit"));
        assertEquals("/workspace", valueAfter(argv, "--workdir"));
        assertEquals("/home/u/.forvum/workspace:/workspace:ro", valueAfter(argv, "--volume"),
                "the workspace is bind-mounted read-only by default");
        // The image precedes the payload; the tail is [image, /bin/sh, -c, code] for the default interpreter.
        assertEquals("python:3.12-slim", argv.get(argv.size() - 4));
        assertEquals("/bin/sh", argv.get(argv.size() - 3));
        assertEquals("-c", argv.get(argv.size() - 2));
        assertEquals("print('hi')", argv.get(argv.size() - 1), "the code is the last argument");
    }

    @Test
    void argvModeExecsTheVectorDirectlyAfterTheImage() {
        List<String> argv = SandboxCommand.build(
                "docker", spec(), Path.of("/ws"), null, List.of("echo", "hello"), false);

        int image = argv.indexOf("python:3.12-slim");
        assertEquals(List.of("echo", "hello"), argv.subList(image + 1, argv.size()),
                "argv mode runs the explicit vector, no interpreter");
        assertFalse(argv.contains("/bin/sh"), "the interpreter is not used in argv mode");
    }

    @Test
    void writableMountIsRwWhenRequested() {
        List<String> argv = SandboxCommand.build(
                "podman", spec(), Path.of("/ws"), "x", null, true);

        assertEquals("/ws:/workspace:rw", valueAfter(argv, "--volume"),
                "writable=true opts into a read-write mount");
    }

    @Test
    void networkIsBridgedOnlyWhenExplicitlyAllowed() {
        SandboxConfig.Spec allowed = new SandboxConfig.Spec("img", Optional.empty(), "none", true,
                "1000:1000", "1", "256m", "/workspace", List.of("/bin/sh", "-c"), 60);

        List<String> argv = SandboxCommand.build("podman", allowed, Path.of("/ws"), "x", null, false);

        assertEquals("bridge", valueAfter(argv, "--network"),
                "allowNetwork=true is the only way to get egress");
    }

    @Test
    void aBlankConfiguredNetworkNeverSilentlyOpensTheNetwork() {
        SandboxConfig.Spec blankNet = new SandboxConfig.Spec("img", Optional.empty(), "  ", false,
                "1000:1000", "1", "256m", "/workspace", List.of("/bin/sh", "-c"), 60);

        List<String> argv = SandboxCommand.build("podman", blankNet, Path.of("/ws"), "x", null, false);

        assertEquals("none", valueAfter(argv, "--network"),
                "a misconfigured blank network falls back to none, not an open network");
    }

    @Test
    void requiresExactlyOneOfCodeOrArgv() {
        SandboxConfig.Spec s = spec();
        // Neither.
        assertThrows(SandboxExecException.class,
                () -> SandboxCommand.build("podman", s, Path.of("/ws"), null, null, false));
        assertThrows(SandboxExecException.class,
                () -> SandboxCommand.build("podman", s, Path.of("/ws"), "  ", List.of(), false));
        // Both.
        assertThrows(SandboxExecException.class,
                () -> SandboxCommand.build("podman", s, Path.of("/ws"), "x", List.of("y"), false));
    }
}
