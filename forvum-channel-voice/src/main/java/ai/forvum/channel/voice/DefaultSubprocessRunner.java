package ai.forvum.channel.voice;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link SubprocessRunner}: a thin {@link ProcessBuilder} wrapper. It launches the
 * external binary, drains its standard output and standard error on dedicated virtual threads (so a
 * chatty process can never deadlock by filling a pipe buffer while we block on the other stream — the
 * classic {@code ProcessBuilder} trap), writes {@code stdin} when supplied, and force-kills the process
 * if it overruns {@code timeout}.
 *
 * <p>Per CLAUDE.md §3.8 the wait is BLOCKING (not reactive); the stream drains and the
 * {@link Process#waitFor} run on a {@link Executors#newVirtualThreadPerTaskExecutor() virtual-thread}
 * executor, so the whole driver is on virtual threads and links no native audio code.
 */
@ApplicationScoped
public class DefaultSubprocessRunner implements SubprocessRunner {

    @Override
    public Result run(List<String> argv, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        Process process = builder.start();

        // Drain stdout/stderr concurrently so a full pipe buffer can never block the process (and us).
        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> out = drains.submit(() -> readFully(process.getInputStream()));
            Future<String> err = drains.submit(() -> readFully(process.getErrorStream()));

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
                process.destroyForcibly();
                process.waitFor();
                return new Result(Result.TIMED_OUT, drain(out), drain(err));
            }
            return new Result(process.exitValue(), drain(out), drain(err));
        }
    }

    /** Read a captured stream's {@link Future} result, surfacing a drain IO failure as unchecked. */
    private static String drain(Future<String> future) throws InterruptedException {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException io) {
                throw io;
            }
            throw new UncheckedIOException(new IOException("Failed to drain a subprocess stream.", cause));
        }
    }

    /** Read an input stream fully to a UTF-8 string, wrapping an IO failure as unchecked for the drain. */
    private static String readFully(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read a subprocess stream.", e);
        }
    }
}
