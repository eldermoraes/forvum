package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The {@link DefaultSubprocessRunner} {@link ProcessBuilder} seam, driven against tiny inline
 * {@code /bin/sh} programs (no real piper needed) — the module-private copy of the voice runner PLUS the
 * #186 environment scrub. It exercises the actual {@code java.lang.Process} substrate the design relies on:
 * stdin write-then-close, stdout/stderr capture + exit code, the scrubbed environment, the chatty-output
 * no-deadlock guard, the kill-on-timeout sentinel, and the bounded post-settle drain (an escaped
 * descendant must not hang the caller). Skips gracefully on a platform lacking the expected binaries.
 */
class DefaultSubprocessRunnerTest {

    private final SubprocessRunner runner = new DefaultSubprocessRunner();

    private static String binaryOrSkip(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        assumeTrue(false, "no POSIX binary among " + List.of(candidates) + " on this platform");
        return null; // unreachable
    }

    private SubprocessRunner.Result sh(String script, String stdin, Duration timeout) throws Exception {
        String sh = binaryOrSkip("/bin/sh", "/usr/bin/sh");
        return runner.run(List.of(sh, "-c", script), stdin, timeout);
    }

    @Test
    void capturesStdoutAndAZeroExit() throws Exception {
        SubprocessRunner.Result r = sh("printf 'hello'", null, Duration.ofSeconds(10));

        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertEquals("hello", r.stdout());
        assertFalse(r.timedOut());
    }

    @Test
    void capturesStderrAndANonZeroExit() throws Exception {
        SubprocessRunner.Result r = sh("printf 'oops' >&2; exit 3", null, Duration.ofSeconds(10));

        assertFalse(r.ok());
        assertEquals(3, r.exitCode());
        assertEquals("oops", r.stderr());
    }

    @Test
    void pipesStdinToTheProcessAndClosesIt() throws Exception {
        // `cat` echoes whatever we feed on stdin and then sees EOF (stdin is closed after the write) — the
        // piper-is-stdin-FED contract. It must NOT block on an open stdin until the timeout.
        SubprocessRunner.Result r = sh("cat", "the text to synthesize", Duration.ofSeconds(10));

        assertTrue(r.ok());
        assertEquals("the text to synthesize", r.stdout());
    }

    @Test
    void theEnvironmentIsScrubbedToPathHomeLang() throws Exception {
        // The child env carries ONLY the passthrough keys. We cannot set the parent env from a test, but
        // `env` lists the child's environment, which must contain nothing outside PATH/HOME/LANG.
        String printenv = binaryOrSkip("/usr/bin/env", "/bin/env");
        SubprocessRunner.Result r = runner.run(List.of(printenv), null, Duration.ofSeconds(10));

        for (String line : r.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String key = line.substring(0, Math.max(0, line.indexOf('=')));
            assertTrue(List.of("PATH", "HOME", "LANG").contains(key),
                    "the child env carries only PATH/HOME/LANG (#186 scrub), saw: " + key);
        }
    }

    @Test
    void aChattyProcessDoesNotDeadlockAndIsFullyCaptured() throws Exception {
        // A large stdout payload would deadlock a naive single-threaded drain (pipe buffer fills before we
        // read it) — the runner drains stdout/stderr on separate virtual threads to avoid that.
        SubprocessRunner.Result r = sh("yes x | head -c 200000", null, Duration.ofSeconds(20));

        assertTrue(r.ok());
        assertEquals(200000, r.stdout().length());
    }

    @Test
    void forceKillsAProcessThatOverrunsItsTimeout() throws Exception {
        SubprocessRunner.Result r = sh("sleep 30", null, Duration.ofMillis(300));

        assertTrue(r.timedOut(), "a process exceeding the timeout is force-killed");
        assertEquals(SubprocessRunner.Result.TIMED_OUT, r.exitCode());
    }

    @Test
    void doesNotHangWhenAnEscapedDescendantHoldsTheOutputPipeOpen() throws Exception {
        // The shell exits 0 immediately, but a backgrounded grandchild in a subshell is reparented and
        // inherits the stdout pipe's write end, holding it open for 20s. A naive `readAllBytes()` (or an
        // unbounded join) would wait for that EOF the whole 20s. The bounded drain must proceed.
        long startNanos = System.nanoTime();
        SubprocessRunner.Result r = sh("( sleep 20 & ) ; printf done", null, Duration.ofSeconds(30));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(0, r.exitCode(), "the shell exited 0 well before the timeout");
        assertFalse(r.timedOut());
        assertTrue(elapsedMillis < 10_000,
                "the bounded drain must not wait on an escaped descendant holding the pipe (took "
                        + elapsedMillis + "ms)");
    }

    @Test
    void readFullySwallowsAStreamReadErrorToTheEmptyString() {
        // A post-kill read error (the stream is closed under the drain) must yield "" rather than
        // propagate — the drain runs on a virtual thread and must not crash.
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated post-kill stream failure");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated post-kill stream failure");
            }
        };

        assertEquals("", DefaultSubprocessRunner.readFully(broken));
    }
}
