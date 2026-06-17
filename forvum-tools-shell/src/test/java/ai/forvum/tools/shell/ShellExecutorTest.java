package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link ShellExecutor} — the pure ProcessBuilder LIST-form launcher. These tests launch
 * REAL child processes (POSIX coreutils), so they exercise the actual {@code java.lang.Process} substrate
 * the design relies on; the same APIs run in the GraalVM native image (the native-proof obligation is
 * additionally discharged by the forvum-app native IT — see notes). Tests skip gracefully on a platform
 * lacking the expected binaries.
 */
class ShellExecutorTest {

    private final ShellExecutor executor = new ShellExecutor();

    /** The first of {@code candidates} that exists, or skip the test. */
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
    void runsACommandAndCapturesStdout(@TempDir Path dir) {
        String echo = binaryOrSkip("/bin/echo", "/usr/bin/echo");

        String out = executor.run(List.of(echo, "hello", "world"), dir, 10);

        assertEquals("hello world\n", out, "stdout is captured verbatim (merged stream)");
    }

    @Test
    void mergesStderrIntoTheCapturedOutput(@TempDir Path dir) {
        // `ls` of a missing path writes to stderr and exits non-zero; redirectErrorStream merges it.
        String ls = binaryOrSkip("/bin/ls", "/usr/bin/ls");

        ShellExecException e = assertThrows(ShellExecException.class,
                () -> executor.run(List.of(ls, "/no/such/path/forvum-test"), dir, 10));
        assertTrue(e.getMessage().contains("exited with status"),
                "a non-zero exit is reported, got: " + e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("no such")
                        || e.getMessage().toLowerCase().contains("cannot"),
                "the merged stderr is carried in the exception message, got: " + e.getMessage());
    }

    @Test
    void aZeroExitCommandReturnsItsOutput(@TempDir Path dir) {
        String trueBin = binaryOrSkip("/usr/bin/true", "/bin/true");

        String out = executor.run(List.of(trueBin), dir, 10);

        assertEquals("", out, "/usr/bin/true exits 0 with no output");
    }

    @Test
    void aNonZeroExitThrowsShellExecException(@TempDir Path dir) {
        String falseBin = binaryOrSkip("/usr/bin/false", "/bin/false");

        assertThrows(ShellExecException.class, () -> executor.run(List.of(falseBin), dir, 10));
    }

    @Test
    void aTimeoutKillsTheProcessAndThrows(@TempDir Path dir) {
        String sleep = binaryOrSkip("/bin/sleep", "/usr/bin/sleep");

        long start = System.nanoTime();
        ShellExecException e = assertThrows(ShellExecException.class,
                () -> executor.run(List.of(sleep, "30"), dir, 1));
        long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertTrue(e.getMessage().contains("timed out"), "a timeout is reported, got: " + e.getMessage());
        assertTrue(elapsedSeconds < 10,
                "the timeout fired near 1s, not after the 30s sleep (elapsed=" + elapsedSeconds + "s)");
    }

    @Test
    void theEnvironmentIsScrubbedToPathHomeLang(@TempDir Path dir) {
        String printenv = binaryOrSkip("/usr/bin/printenv", "/bin/printenv");
        // A non-passthrough variable must NOT be visible to the child. We cannot set the parent env from
        // a test, but we can assert the child sees ONLY the passthrough keys by listing its environment.
        String out = executor.run(List.of(printenv), dir, 10);

        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String key = line.substring(0, Math.max(0, line.indexOf('=')));
            assertTrue(List.of("PATH", "HOME", "LANG").contains(key),
                    "the child env carries only PATH/HOME/LANG, saw: " + key);
        }
    }

    @Test
    void runsInTheGivenWorkingDirectory(@TempDir Path dir) throws Exception {
        String pwd = binaryOrSkip("/bin/pwd", "/usr/bin/pwd");
        Path sub = Files.createDirectories(dir.resolve("work"));

        String out = executor.run(List.of(pwd), sub, 10);

        assertEquals(sub.toRealPath().toString(), out.strip(),
                "the process runs in the supplied working directory");
    }

    @Test
    void argumentsAreNotInterpretedByAShell(@TempDir Path dir) {
        // LIST form: a metacharacter-laden argument is passed verbatim, never interpreted (no `sh -c`).
        String echo = binaryOrSkip("/bin/echo", "/usr/bin/echo");

        String out = executor.run(List.of(echo, "$(touch pwned); a | b > c & d"), dir, 10);

        assertEquals("$(touch pwned); a | b > c & d\n", out,
                "shell metacharacters in an argument are inert (no shell interpreter)");
        assertFalse(Files.exists(dir.resolve("pwned")),
                "the command substitution did NOT run — there is no shell");
    }
}
