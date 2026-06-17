package ai.forvum.channel.voice;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * The {@link ProcessBuilder} seam the voice pipeline uses to drive its two OPERATOR-installed external
 * binaries — whisper.cpp (STT) and piper (TTS) — and an optional ffmpeg transcode. A subprocess driver
 * is exactly the native-safe surface the spec mandates (P2-3 / #28): {@code ProcessBuilder} is a plain
 * posix fork/exec wrapper fully supported by GraalVM native-image (no reflection, no JNI from the Java
 * side), so all the heavy audio work happens in those EXTERNAL processes and the JVM never links an
 * audio codec.
 *
 * <p>It is an interface so the STT/TTS logic is unit-tested against a committed STUB whisper/piper
 * script (or an in-test fake) with no real whisper/piper present — the way the native-viability spike
 * proves the design. The default implementation is {@link DefaultSubprocessRunner}; CLAUDE.md §3.8: the
 * subprocess wait is BLOCKING on a virtual thread ({@link Process#waitFor}), never a Mutiny/reactive
 * pipeline.
 */
public interface SubprocessRunner {

    /**
     * Run {@code argv} as an external process, feeding {@code stdin} (when non-null) to its standard
     * input and capturing its standard output and standard error, killing it with
     * {@link Process#destroyForcibly} if it does not finish within {@code timeout}.
     *
     * @param argv    the command and its arguments ({@code argv[0]} is the binary path)
     * @param stdin   text to write to the process's standard input, or {@code null} to write nothing
     * @param timeout the maximum time to wait before force-killing the process
     * @return the captured {@link Result}
     * @throws IOException          if the process cannot be started or its streams cannot be read
     * @throws InterruptedException if the calling (virtual) thread is interrupted while waiting
     */
    Result run(List<String> argv, String stdin, Duration timeout) throws IOException, InterruptedException;

    /**
     * The captured outcome of a subprocess run.
     *
     * @param exitCode the process exit code; {@link #TIMED_OUT} when the process was force-killed on
     *                 timeout (a real binary never returns this sentinel)
     * @param stdout   the full standard output captured as a string
     * @param stderr   the full standard error captured as a string
     */
    record Result(int exitCode, String stdout, String stderr) {

        /** Exit code stamped on a {@link Result} when the process was force-killed on timeout. */
        public static final int TIMED_OUT = -1;

        /** Whether the process exited normally (exit code 0). */
        public boolean ok() {
            return exitCode == 0;
        }

        /** Whether the process was force-killed because it exceeded its timeout. */
        public boolean timedOut() {
            return exitCode == TIMED_OUT;
        }
    }
}
