package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE test that needs a real piper (#186), {@code @Tag("live")} so it is default-off in CI (nightly /
 * manual per repo policy). It drives a single real synthesis and asserts the output is a valid WAV (the
 * {@code RIFF....WAVE} magic) of non-trivial size. Configure via env:
 *
 * <pre>
 *   FORVUM_TTS_PIPER_BIN=/opt/piper/piper
 *   FORVUM_TTS_PIPER_VOICE=/opt/voices/en_US-amy-medium.onnx
 * </pre>
 *
 * <p>Run with {@code ./mvnw -pl forvum-tools-tts test -Dgroups=live -DexcludedGroups=}. The engine's
 * belt/RBAC/audit enforcement of {@code MEDIA_SYNTHESIZE} is engine machinery pinned generically
 * elsewhere; this test only proves the piper subprocess round-trip.
 */
@Tag("live")
class TtsSpeakPiperLiveTest {

    @Test
    void synthesizesAValidWavWithRealPiper(@TempDir Path workspace) throws IOException {
        String piperBin = System.getenv("FORVUM_TTS_PIPER_BIN");
        String piperVoice = System.getenv("FORVUM_TTS_PIPER_VOICE");
        assumeTrue(piperBin != null && !piperBin.isBlank()
                        && Files.isExecutable(Path.of(piperBin)),
                "set FORVUM_TTS_PIPER_BIN to an executable piper binary");
        assumeTrue(piperVoice != null && !piperVoice.isBlank()
                        && Files.isRegularFile(Path.of(piperVoice)),
                "set FORVUM_TTS_PIPER_VOICE to an .onnx voice model");

        TtsConfig.Spec spec = new TtsConfig.Spec(
                Optional.of(piperBin), Optional.of(piperVoice), Map.of(), 120);
        TtsSynthesizer synthesizer = new TtsSynthesizer(new DefaultSubprocessRunner(), workspace);

        String result = synthesizer.synthesize("Hello from Forvum.", null, spec);

        assertTrue(result.contains("tts/speech-"), result);
        try (var files = Files.list(workspace.resolve("tts"))) {
            Path wav = files.filter(p -> p.getFileName().toString().endsWith(".wav"))
                    .findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(wav);
            assertTrue(bytes.length > 44, "a real WAV has at least a 44-byte header + audio");
            String magic = new String(bytes, 0, 4);
            assertTrue("RIFF".equals(magic), "the output starts with the RIFF WAV magic, got: " + magic);
            assertTrue("WAVE".equals(new String(bytes, 8, 4)), "the WAVE format tag is present");
        }
    }
}
