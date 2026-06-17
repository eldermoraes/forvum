package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * The {@link DefaultSubprocessRunner} {@link ProcessBuilder} seam, driven against tiny inline
 * {@code /bin/sh} programs (no real whisper/piper needed): stdout/stderr capture, the exit code, stdin
 * piping, and the kill-on-timeout sentinel. A plain POSIX test (the CI matrix is linux + macOS, both
 * with {@code /bin/sh}).
 */
class SubprocessRunnerTest {

    private final SubprocessRunner runner = new DefaultSubprocessRunner();

    private SubprocessRunner.Result sh(String script, String stdin, Duration timeout) throws Exception {
        return runner.run(List.of("/bin/sh", "-c", script), stdin, timeout);
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
    void pipesStdinToTheProcess() throws Exception {
        // `cat` echoes whatever we feed on stdin — proves the stdin write reaches the process.
        SubprocessRunner.Result r = sh("cat", "piped reply text", Duration.ofSeconds(10));

        assertTrue(r.ok());
        assertEquals("piped reply text", r.stdout());
    }

    @Test
    void aChattyProcessDoesNotDeadlockAndIsFullyCaptured() throws Exception {
        // A large stdout payload would deadlock a naive single-threaded drain (pipe buffer fills before
        // we read it) — the runner drains stdout/stderr on separate virtual threads to avoid that.
        SubprocessRunner.Result r =
                sh("yes x | head -c 200000", null, Duration.ofSeconds(20));

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
        // The shell itself exits 0 immediately, but a backgrounded grandchild in a subshell is reparented
        // and inherits the stdout pipe's write end, holding it open for 20s. A naive `readAllBytes()` (or
        // an unbounded `Future.get()` / a blocking ExecutorService close) would wait for that EOF the whole
        // 20s. The drain must be bounded so an escaped/daemonizing descendant cannot hang the worker far
        // past the process's own completion.
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
        // propagate — the drain runs on a virtual thread and must not crash, and the caller judges on the
        // exit code / timeout sentinel. A rethrow here would surface the IOException and fail this test
        // (the green-for-wrong-reason guard the [M4] lesson warns about).
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
