package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link TtsSynthesizer} orchestration (mirrors {@code VoicePipeline} synthesis + the M18
 * green-for-wrong-reason discipline): the argv/stdin/timeout handed to the runner, the workspace-confined
 * output naming, the collision policy, voice resolution, and every failure branch — all with a scripted
 * {@link FakeRunner}, no real piper. This is also the hermetic "config-absent degrade" seam (an
 * unconfigured spec → the actionable not-configured message).
 */
class TtsSynthesizerTest {

    /** Records the argv/stdin/timeout, returns a scripted Result, and optionally writes bytes to -f. */
    private static final class FakeRunner implements SubprocessRunner {
        List<String> argv;
        String stdin;
        Duration timeout;
        int callCount;
        private final Result scripted;
        private final byte[] outputBytes;

        FakeRunner(Result scripted, byte[] outputBytes) {
            this.scripted = scripted;
            this.outputBytes = outputBytes;
        }

        /** A successful runner that writes {@code bytes} to the {@code -f} output path (models piper). */
        static FakeRunner writing(byte[] bytes) {
            return new FakeRunner(new Result(0, "", ""), bytes);
        }

        @Override
        public Result run(List<String> argv, String stdin, Duration timeout) throws IOException {
            this.argv = argv;
            this.stdin = stdin;
            this.timeout = timeout;
            this.callCount++;
            if (outputBytes != null) {
                Path out = outputPath(argv);
                Files.write(out, outputBytes);
            }
            return scripted;
        }

        /** The value passed with the {@code -f} flag (piper's output file). */
        static Path outputPath(List<String> argv) {
            int i = argv.indexOf("-f");
            return Path.of(argv.get(i + 1));
        }
    }

    private static TtsConfig.Spec readySpec() {
        return new TtsConfig.Spec(Optional.of("/opt/piper"), Optional.of("/v/def.onnx"),
                Map.of("amy", "/v/amy.onnx"), 120);
    }

    @Test
    void synthesizesWithTheExpectedArgvTextOnStdinAndWavUnderTheWorkspace(@TempDir Path ws) {
        FakeRunner runner = FakeRunner.writing("RIFFxxxxWAVEfmt ".getBytes(StandardCharsets.UTF_8));
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        String result = synthesizer.synthesize("hello world", null, readySpec());

        // argv is exactly [piperBin, -m, <defaultVoice>, -f, <temp>]
        assertEquals("/opt/piper", runner.argv.get(0));
        assertEquals("-m", runner.argv.get(1));
        assertEquals("/v/def.onnx", runner.argv.get(2));
        assertEquals("-f", runner.argv.get(3));
        assertEquals(5, runner.argv.size());
        // the text rides stdin verbatim
        assertEquals("hello world", runner.stdin);
        assertEquals(Duration.ofSeconds(120), runner.timeout);
        // the final WAV lives under <workspace>/tts/ with the generated name; the temp is gone
        assertTrue(result.contains("tts/speech-"), "result names the workspace-relative path: " + result);
        assertTrue(result.contains("absolute: " + ws.toAbsolutePath().normalize() + "/tts/speech-"),
                "result carries the absolute path: " + result);
        assertTrue(result.contains("16 bytes"), "result reports the byte size: " + result);
        try (var files = Files.list(ws.resolve("tts"))) {
            long wavCount = files.filter(p -> p.getFileName().toString().endsWith(".wav")).count();
            assertEquals(1, wavCount, "exactly one WAV, and no leftover .wav.tmp");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void twoCallsProduceDistinctFileNames(@TempDir Path ws) throws IOException {
        FakeRunner runner = FakeRunner.writing(new byte[] {1, 2, 3, 4});
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        synthesizer.synthesize("first", null, readySpec());
        synthesizer.synthesize("second", null, readySpec());

        try (var files = Files.list(ws.resolve("tts"))) {
            long wavCount = files.filter(p -> p.getFileName().toString().endsWith(".wav")).count();
            assertEquals(2, wavCount, "distinct names → both WAVs coexist (collision-free by construction)");
        }
    }

    @Test
    void aNamedVoiceResolvesFromTheMap(@TempDir Path ws) {
        FakeRunner runner = FakeRunner.writing(new byte[] {1});
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        synthesizer.synthesize("hi", "amy", readySpec());

        assertEquals("/v/amy.onnx", runner.argv.get(2), "the named voice's ONNX path is passed via -m");
    }

    @Test
    void anUnknownVoiceIsRejectedWithoutRunningPiper(@TempDir Path ws) {
        FakeRunner runner = FakeRunner.writing(new byte[] {1});
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        assertThrows(TtsException.class, () -> synthesizer.synthesize("hi", "bogus", readySpec()));
        assertEquals(0, runner.callCount, "an unknown voice fails before launching piper");
    }

    @Test
    void aBlankTextIsRejected(@TempDir Path ws) {
        FakeRunner runner = FakeRunner.writing(new byte[] {1});
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        assertThrows(TtsException.class, () -> synthesizer.synthesize("   ", null, readySpec()));
        assertThrows(TtsException.class, () -> synthesizer.synthesize(null, null, readySpec()));
        assertEquals(0, runner.callCount);
    }

    @Test
    void anUnconfiguredSpecReturnsTheActionableNotConfiguredMessage(@TempDir Path ws) {
        FakeRunner runner = FakeRunner.writing(new byte[] {1});
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        TtsException e = assertThrows(TtsException.class,
                () -> synthesizer.synthesize("hi", null, TtsConfig.Spec.unconfigured()));
        assertTrue(e.getMessage().contains("not configured"), e.getMessage());
        assertTrue(e.getMessage().contains("tools/tts.json"), e.getMessage());
        assertEquals(0, runner.callCount, "an unconfigured tool never launches piper");
    }

    @Test
    void aNonZeroExitCarriesTheCodeAndStderrTailAndDeletesTheTemp(@TempDir Path ws) throws IOException {
        // The runner "runs" but exits 2 and writes nothing.
        FakeRunner runner = new FakeRunner(new SubprocessRunner.Result(2, "", "piper: bad voice model"),
                null);
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        TtsException e = assertThrows(TtsException.class,
                () -> synthesizer.synthesize("hi", null, readySpec()));
        assertTrue(e.getMessage().contains("status 2"), e.getMessage());
        assertTrue(e.getMessage().contains("bad voice model"), "the stderr tail rides the message");
        assertNoTempLeftOver(ws);
    }

    @Test
    void aTimeoutIsReportedAndTheTempCleaned(@TempDir Path ws) throws IOException {
        FakeRunner runner = new FakeRunner(
                new SubprocessRunner.Result(SubprocessRunner.Result.TIMED_OUT, "", ""), null);
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        TtsException e = assertThrows(TtsException.class,
                () -> synthesizer.synthesize("hi", null, readySpec()));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertNoTempLeftOver(ws);
    }

    @Test
    void exitZeroButNoAudioIsAnError(@TempDir Path ws) throws IOException {
        // exit 0 but the runner writes nothing → the output file is missing/empty.
        FakeRunner runner = new FakeRunner(new SubprocessRunner.Result(0, "", ""), null);
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, ws);

        TtsException e = assertThrows(TtsException.class,
                () -> synthesizer.synthesize("hi", null, readySpec()));
        assertTrue(e.getMessage().contains("no audio"), e.getMessage());
        assertNoTempLeftOver(ws);
    }

    private static void assertNoTempLeftOver(Path ws) throws IOException {
        Path ttsDir = ws.resolve("tts");
        if (!Files.isDirectory(ttsDir)) {
            return;
        }
        try (var files = Files.list(ttsDir)) {
            assertFalse(files.findAny().isPresent(), "the temp file is cleaned on every failure path");
        }
    }
}
