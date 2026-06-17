package ai.forvum.channel.voice;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The production {@link SubprocessRunner}: a thin {@link ProcessBuilder} wrapper. It launches the
 * external binary, drains its standard output and standard error on dedicated virtual threads (so a
 * chatty process can never deadlock by filling a pipe buffer while we block on the other stream — the
 * classic {@code ProcessBuilder} trap), writes {@code stdin} when supplied, and force-kills the process
 * (and its descendants) if it overruns {@code timeout}.
 *
 * <p>Per CLAUDE.md §3.8 the wait is BLOCKING (not reactive); the stream drains run on
 * {@link Thread#ofVirtual() virtual threads} and the {@link Process#waitFor} runs on the calling
 * (virtual) thread, so the whole driver is on virtual threads and links no native audio code.
 *
 * <p><strong>Bounded drain.</strong> After the process settles (natural exit or forced kill) the calling
 * thread waits for each drain to observe EOF and publish, but only up to {@link #DRAIN_GRACE_MILLIS} —
 * mirroring {@code ShellExecutor}. A force-killed process can leave a double-forked / reparented
 * descendant that escaped {@code descendants()} yet keeps the output pipe's write end open; a naive
 * {@code readAllBytes()} (or an {@code ExecutorService} whose {@code close()} awaits its tasks) would then
 * wait for an EOF that only arrives when that descendant exits, hanging the worker far past the process's
 * own completion. The bounded join proceeds with whatever each drain captured. The drain threads are
 * virtual (always daemon), so an abandoned blocked drain never keeps the JVM alive.
 */
@ApplicationScoped
public class DefaultSubprocessRunner implements SubprocessRunner {

    /**
     * Grace given to each drain thread to observe EOF after the process settles before the calling thread
     * gives up on it. Bounds the wait so an escaped/reparented descendant holding an output pipe open
     * cannot hang the worker; on expiry we proceed with whatever was captured.
     */
    static final long DRAIN_GRACE_MILLIS = 2_000;

    @Override
    public Result run(List<String> argv, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        Process process = builder.start();

        // Drain stdout/stderr concurrently so a full pipe buffer can never block the process (and us).
        AtomicReference<String> out = new AtomicReference<>("");
        AtomicReference<String> err = new AtomicReference<>("");
        Thread outDrain = Thread.ofVirtual().name("voice-subproc-stdout")
                .start(() -> out.set(readFully(process.getInputStream())));
        Thread errDrain = Thread.ofVirtual().name("voice-subproc-stderr")
                .start(() -> err.set(readFully(process.getErrorStream())));

        if (stdin != null) {
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The process may close stdin early (e.g. it has all it needs); that is not an error
                // by itself — the exit code / output is what the caller judges on.
            }
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor();
        }
        // Let the drains observe EOF (after the natural exit or the forced kill) and publish — bounded, so
        // an escaped descendant holding an output pipe open cannot hang us past the process's completion.
        outDrain.join(DRAIN_GRACE_MILLIS);
        errDrain.join(DRAIN_GRACE_MILLIS);

        int exitCode = finished ? process.exitValue() : Result.TIMED_OUT;
        return new Result(exitCode, out.get(), err.get());
    }

    /**
     * Read an input stream fully to a UTF-8 string. A read error after a forced kill is expected (the
     * stream is closed under us), so it yields the empty string rather than propagating — the caller
     * judges on the exit code / timeout sentinel, not on a post-kill drain failure. Package-private so a
     * test can pin the swallow directly with a throwing stream.
     */
    static String readFully(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The stream is closed under us when the process is destroyed; a read error after a kill is
            // expected, so capture nothing rather than crashing the drain thread.
            return "";
        }
    }
}
