package ai.forvum.tools.tts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates one {@code tts.speak} call into a synthesized WAV under the workspace (#186): resolve the
 * voice, ensure {@code <workspace>/tts/}, write to a temp file, drive piper
 * ({@code [piperBin, -m, <voice>, -f, <temp>]} with the TEXT on stdin), judge the result, verify a
 * non-empty output, atomically move it into place, and return the workspace-relative path plus the byte
 * size. Every failure is a {@link TtsException} (the engine audits {@code error} and rethrows it to the
 * model; the turn completes).
 *
 * <p>Pure orchestration (Quarkus-free, no CDI): the {@link SubprocessRunner} and the workspace root are
 * constructor-injected, so it is directly unit-testable with a fake runner (mirroring the voice
 * {@code VoicePipeline}). Output naming is generated-unique
 * ({@code speech-<yyyyMMdd-HHmmss>-<8 hex random>.wav}) so collisions are impossible by construction and
 * there is NO model-supplied output path to confine.
 */
public final class TtsSynthesizer {

    /** The workspace subdirectory every synthesized WAV is written to (fixed, no config surface). */
    static final String TTS_SUBDIR = "tts";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** How much of piper's stderr rides back into an error message (bounded — piper's is small). */
    private static final int STDERR_TAIL = 500;

    private final SubprocessRunner runner;
    private final Path workspaceRoot;

    public TtsSynthesizer(SubprocessRunner runner, Path workspaceRoot) {
        this.runner = runner;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Synthesize {@code text} to a WAV under {@code <workspace>/tts/} using {@code spec}'s piper binary and
     * the voice resolved from {@code voiceName} (blank/null = the default {@code piperVoice}).
     *
     * @param text      the text to synthesize (must be non-blank)
     * @param voiceName the requested voice name, or {@code null}/blank for the default voice
     * @param spec      the resolved {@code tools/tts.json} configuration
     * @return the workspace-relative path to the generated WAV (e.g. {@code tts/speech-....wav})
     * @throws TtsException on any configuration, argument, or synthesis failure
     */
    public String synthesize(String text, String voiceName, TtsConfig.Spec spec) {
        if (text == null || text.isBlank()) {
            throw new TtsException("tts.speak requires a non-blank 'text' argument.");
        }
        if (!spec.isReady()) {
            throw new TtsException("tts.speak is not configured: create $FORVUM_HOME/tools/tts.json with "
                    + "piperBin (path to the piper binary) and piperVoice (path to an .onnx voice model).");
        }
        String piperBin = spec.piperBin().orElseThrow(() -> new TtsException(
                "tts.speak is not configured: set piperBin in $FORVUM_HOME/tools/tts.json."));
        String voice = spec.resolveVoice(voiceName);

        Path ttsDir = workspaceRoot.resolve(TTS_SUBDIR);
        Path temp;
        try {
            Files.createDirectories(ttsDir);
            temp = Files.createTempFile(ttsDir, "tts-", ".wav.tmp");
        } catch (IOException e) {
            throw new TtsException("tts.speak could not prepare the output directory "
                    + workspaceRoot.resolve(TTS_SUBDIR) + ": " + e.getMessage());
        }

        try {
            SubprocessRunner.Result result;
            try {
                result = runner.run(
                        List.of(piperBin, "-m", voice, "-f", temp.toString()),
                        text, Duration.ofSeconds(spec.timeoutSeconds()));
            } catch (IOException e) {
                throw new TtsException("tts.speak could not start piper '" + piperBin + "': "
                        + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TtsException("tts.speak was interrupted while running piper '" + piperBin + "'.");
            }

            if (result.timedOut()) {
                throw new TtsException("tts.speak timed out after " + spec.timeoutSeconds()
                        + "s; piper was force-killed.");
            }
            if (!result.ok()) {
                throw new TtsException("tts.speak: piper '" + piperBin + "' exited with status "
                        + result.exitCode() + "." + stderrTail(result.stderr()));
            }
            long size = sizeOrZero(temp);
            if (size <= 0) {
                throw new TtsException("tts.speak: piper exited 0 but produced no audio."
                        + stderrTail(result.stderr()));
            }

            Path outFile = ttsDir.resolve(generatedName());
            move(temp, outFile);
            Path relative = workspaceRoot.relativize(outFile);
            return "wrote " + size + " bytes to " + relative + " (absolute: " + outFile + ").";
        } catch (TtsException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    /**
     * The generated, collision-free output file name: {@code speech-<yyyyMMdd-HHmmss>-<8 hex>.wav}. The
     * suffix is the first 8 hex chars of a random {@link UUID} — native-safe (unlike a static
     * {@code SecureRandom} field, which native-image rejects in the image heap for its cached seed).
     */
    private static String generatedName() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "speech-" + LocalDateTime.now().format(STAMP) + "-" + suffix + ".wav";
    }

    /** Atomically move {@code temp} to {@code out}, falling back to a plain replace where unsupported. */
    private static void move(Path temp, Path out) {
        try {
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            try {
                Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new TtsException("tts.speak could not write the output WAV " + out + ": "
                        + e.getMessage());
            }
        }
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** A bounded tail of piper's stderr for an error message (piper's stderr is small; keep it short). */
    private static String stderrTail(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String trimmed = stderr.strip();
        String tail = trimmed.length() > STDERR_TAIL
                ? trimmed.substring(trimmed.length() - STDERR_TAIL)
                : trimmed;
        return " piper stderr: " + tail;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; never fatal.
        }
    }
}
