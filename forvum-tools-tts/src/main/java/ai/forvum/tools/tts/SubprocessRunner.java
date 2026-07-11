package ai.forvum.tools.tts;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * The {@link ProcessBuilder} seam the TTS tool uses to drive the OPERATOR-installed piper binary. A
 * subprocess driver is exactly the native-safe surface #186 mandates: {@code ProcessBuilder} is a plain
 * posix fork/exec wrapper fully supported by GraalVM native-image (no reflection, no JNI from the Java
 * side), so all the audio synthesis happens in the EXTERNAL piper process and the JVM never links an
 * audio codec.
 *
 * <p>It is an interface so {@link TtsSynthesizer} is unit-tested against a committed fake (recording the
 * argv/stdin/timeout and, for the happy path, writing bytes to the {@code -f} output file) with no real
 * piper present. The default implementation is {@link DefaultSubprocessRunner}; CLAUDE.md §3.8: the
 * subprocess wait is BLOCKING on a virtual thread ({@link Process#waitFor}), never a Mutiny/reactive
 * pipeline.
 *
 * <p>This is a MODULE-PRIVATE copy of the {@code forvum-channel-voice} subprocess pair (a Layer-3 plugin
 * cannot depend on a sibling module), extended with the #186-mandated environment scrub to
 * {@code {PATH, HOME, LANG}} (the {@code ShellExecutor} recipe).
 */
public interface SubprocessRunner {

    /**
     * Run {@code argv} as an external process with a SCRUBBED environment ({@code PATH}, {@code HOME},
     * {@code LANG} only), feeding {@code stdin} (when non-null) to its standard input and capturing its
     * standard output and standard error, killing it with {@link Process#destroyForcibly} if it does not
     * finish within {@code timeout}.
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
