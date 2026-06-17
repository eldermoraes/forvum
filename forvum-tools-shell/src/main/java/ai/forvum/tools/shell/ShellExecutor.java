package ai.forvum.tools.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launches a validated {@code argv} vector as a child process and captures its merged output (ULTRAPLAN
 * §9.2.5). Pure (Quarkus-free, no CDI) so it is directly unit-testable.
 *
 * <p>Safety posture:
 * <ul>
 *   <li><strong>LIST form, never a shell.</strong> {@code ProcessBuilder(argv)} execs the program
 *       directly — there is no {@code sh -c} interpreter, so shell metacharacters in arguments are inert
 *       (no injection surface).</li>
 *   <li><strong>Scrubbed environment.</strong> {@code environment().clear()} then re-add exactly
 *       {@code PATH}, {@code HOME}, {@code LANG} from the host environment (DP-10). A command needing
 *       another variable (e.g. {@code TERM}) fails — acceptable for v0.1.</li>
 *   <li><strong>Closed stdin.</strong> The child's stdin is closed immediately after start (it is a
 *       non-interactive exec), so an allowlisted command that reads stdin with no file operand sees EOF at
 *       once instead of blocking until the timeout.</li>
 *   <li><strong>Timeout + forced kill (bounded).</strong> The calling thread does
 *       {@code waitFor(timeout, SECONDS)}; on expiry the process and its descendants are
 *       {@code destroyForcibly()}-ed and a {@link ShellExecException} is thrown. Enforcing the timeout on
 *       the calling thread (NOT inside the blocking stream read) is what makes the timeout actually fire.
 *       The post-settle wait for the drain to publish is itself bounded ({@link #DRAIN_GRACE_MILLIS}) so an
 *       escaped/daemonizing descendant that keeps the output pipe open cannot hang the turn past its
 *       timeout.</li>
 *   <li><strong>Concurrent drain on a virtual thread.</strong> The merged (stdout+stderr) stream is read
 *       on a single dedicated {@link Thread#ofVirtual() virtual} thread, concurrently with the calling
 *       thread's {@code waitFor}, so a command emitting more than the OS pipe buffer cannot deadlock (a
 *       full pipe would otherwise block the child from ever exiting, turning a large-output success into a
 *       timeout). A virtual thread doing a plain blocking {@code InputStream.read} does NOT pin its carrier
 *       (CLAUDE.md §3.8), and no {@code synchronized}/lock is used — the result is handed back via an
 *       {@link AtomicReference}, joined after the process settles.</li>
 *   <li><strong>Size-bounded capture.</strong> At most {@link #MAX_OUTPUT_BYTES} of merged output is read;
 *       a longer stream is truncated with a marker before re-entering the context window (DP-10).</li>
 * </ul>
 */
public final class ShellExecutor {

    /** The maximum number of bytes of merged output captured before truncation (DP-10, fixed for v0.1). */
    static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /**
     * Grace given to the drain thread to observe EOF after the process settles (natural exit or forced
     * kill) before the calling thread gives up on it. Bounds the wait so a child that double-forked /
     * reparented to init — escaping {@code descendants()} and keeping the merged-output write end open —
     * cannot hang the turn past its timeout; we proceed with whatever output was captured.
     */
    private static final long DRAIN_GRACE_MILLIS = 2_000;

    private static final List<String> PASSTHROUGH_ENV = List.of("PATH", "HOME", "LANG");

    /**
     * Run {@code argv} in {@code workingDir} with the given {@code timeoutSeconds}, returning the captured
     * merged (stdout+stderr) output. The caller has already validated {@code argv} against the allowlist
     * and confined {@code workingDir} to the workspace root.
     *
     * @throws ShellExecException if the process exits non-zero or exceeds the timeout
     */
    public String run(List<String> argv, Path workingDir, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        Map<String, String> host = System.getenv();
        env.clear();
        for (String key : PASSTHROUGH_ENV) {
            String value = host.get(key);
            if (value != null) {
                env.put(key, value);
            }
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ShellExecException(
                    "shell.exec could not start '" + argv.get(0) + "': " + e.getMessage());
        }

        // Close the child's stdin immediately: it is a non-interactive exec, so a command that reads stdin
        // (e.g. `cat`/`grep` with no file operand) must see EOF at once rather than block until the timeout.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The child may have already exited; nothing to close.
        }

        // Drain the merged stream concurrently on a virtual thread so a >pipe-buffer output cannot
        // deadlock the child; the calling thread enforces the timeout via waitFor.
        AtomicReference<Capture> captured = new AtomicReference<>(new Capture("", false));
        Thread drain = Thread.ofVirtual().name("shell-exec-drain").start(
                () -> captured.set(readBounded(process.getInputStream())));

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
            }
            // Let the drain thread observe EOF (after natural exit or the forced kill) and publish — but
            // bounded, so an escaped descendant holding the merged-output write end open cannot hang the
            // turn past its timeout. On grace expiry we proceed with whatever the drain has published.
            drain.join(DRAIN_GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new ShellExecException("shell.exec was interrupted while running '" + argv.get(0) + "'.");
        }

        Capture capture = captured.get();
        if (!finished) {
            throw new ShellExecException(
                    "shell.exec timed out after " + timeoutSeconds + "s running '" + argv.get(0)
                  + "'. Captured output before kill:\n" + capture.render());
        }

        int exit = process.exitValue();
        if (exit != 0) {
            throw new ShellExecException(
                    "shell.exec command '" + argv.get(0) + "' exited with status " + exit + ".\n"
                  + capture.render());
        }
        return capture.render();
    }

    /** Read at most {@link #MAX_OUTPUT_BYTES} from the process's merged stream. */
    private static Capture readBounded(InputStream stream) {
        byte[] buffer = new byte[MAX_OUTPUT_BYTES];
        int total = 0;
        boolean truncated = false;
        try (InputStream in = stream) {
            int read;
            while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
            if (total == buffer.length && in.read() != -1) {
                truncated = true;
                // Drain the rest so the process is not blocked on a full pipe (it can then exit).
                byte[] sink = new byte[8192];
                while (in.read(sink) != -1) {
                    // discard
                }
            }
        } catch (IOException e) {
            // The stream is closed when the process is destroyed; a read error after a kill is expected.
            return new Capture(new String(buffer, 0, total, StandardCharsets.UTF_8), truncated);
        }
        return new Capture(new String(buffer, 0, total, StandardCharsets.UTF_8), truncated);
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Captured output and whether it was truncated at the byte cap. */
    private record Capture(String text, boolean truncated) {
        String render() {
            return truncated ? text + "\n[output truncated at " + MAX_OUTPUT_BYTES + " bytes]" : text;
        }
    }
}
