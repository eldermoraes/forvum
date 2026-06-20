package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link SandboxExecutor} — the pure ProcessBuilder LIST-form bounded-drain launcher (the
 * {@code forvum-tools-shell} {@code ShellExecutor} discipline reused). These tests launch REAL child
 * processes (POSIX coreutils) standing in for the container runtime, so they exercise the actual
 * {@code java.lang.Process} substrate, the closed-stdin behavior, the concurrent virtual-thread drain, and
 * the bounded timeout/kill — without needing podman/docker (the live container path is
 * {@code SandboxRunLiveTest}, which skips when no runtime is present). Tests skip gracefully on a platform
 * lacking the expected binaries.
 */
class SandboxExecutorTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    private static String binaryOrSkip(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        assumeTrue(false, "no POSIX binary among " + List.of(candidates) + " on this platform");
        return null; // unreachable
    }

    @Test
    void runsACommandAndCapturesStdout() {
        String echo = binaryOrSkip("/bin/echo", "/usr/bin/echo");

        String out = executor.run(List.of(echo, "hello", "sandbox"), 10);

        assertEquals("hello sandbox\n", out, "stdout is captured verbatim (merged stream)");
    }

    @Test
    void aNonZeroExitThrowsSandboxExecException() {
        String falseBin = binaryOrSkip("/usr/bin/false", "/bin/false");

        SandboxExecException e = assertThrows(SandboxExecException.class,
                () -> executor.run(List.of(falseBin), 10));
        assertTrue(e.getMessage().contains("exited with status"),
                "a non-zero container exit is reported, got: " + e.getMessage());
    }

    @Test
    void aCommandThatReadsStdinSeesEofImmediately() {
        // `cat` with no operand reads stdin. The child's stdin is closed right after start, so it sees EOF
        // at once and exits 0 instead of blocking until the timeout.
        String cat = binaryOrSkip("/bin/cat", "/usr/bin/cat");

        long start = System.nanoTime();
        String out = executor.run(List.of(cat), 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("", out, "cat with closed stdin produces no output and exits 0");
        assertTrue(elapsedMs < 4_000,
                "a stdin-reading command finishes at EOF, not the 5s timeout (elapsed=" + elapsedMs + "ms)");
    }

    @Test
    void aTimeoutKillsTheProcessAndThrows() {
        String sleep = binaryOrSkip("/bin/sleep", "/usr/bin/sleep");

        long start = System.nanoTime();
        SandboxExecException e = assertThrows(SandboxExecException.class,
                () -> executor.run(List.of(sleep, "30"), 1));
        long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertTrue(e.getMessage().contains("timed out"), "a timeout is reported, got: " + e.getMessage());
        assertTrue(elapsedSeconds < 10,
                "the timeout fired near 1s, not after the 30s sleep (elapsed=" + elapsedSeconds + "s)");
    }

    @Test
    void mergesStderrIntoTheCapturedOutput() {
        String ls = binaryOrSkip("/bin/ls", "/usr/bin/ls");

        SandboxExecException e = assertThrows(SandboxExecException.class,
                () -> executor.run(List.of(ls, "/no/such/path/forvum-sandbox-test"), 10));
        assertTrue(e.getMessage().toLowerCase().contains("no such")
                        || e.getMessage().toLowerCase().contains("cannot"),
                "the merged stderr is carried in the exception, got: " + e.getMessage());
    }

    @Test
    void couldNotStartRuntimeThrowsAClearError() {
        SandboxExecException e = assertThrows(SandboxExecException.class,
                () -> executor.run(List.of("/no/such/runtime/podman", "run"), 10));
        assertTrue(e.getMessage().contains("could not start the container runtime"),
                "a missing runtime binary is reported clearly, got: " + e.getMessage());
    }
}
