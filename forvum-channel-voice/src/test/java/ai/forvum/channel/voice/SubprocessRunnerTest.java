package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
