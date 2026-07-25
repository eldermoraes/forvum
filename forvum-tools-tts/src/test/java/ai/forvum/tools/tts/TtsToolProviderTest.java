package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Execution contract for {@link TtsToolProvider#invoke(String, Map)} (M18 Option A): the provider
 * self-dispatches {@code tts.speak}, reads the on-demand config, validates the arguments, and drives the
 * synthesizer — with no reflection. The engine's {@code ToolExecutor} gates belt + RBAC and audits; this
 * test exercises the in-provider dispatch against a fake runner + a {@code @TempDir} home/workspace.
 */
class TtsToolProviderTest {

    /** A runner that writes a WAV-shaped payload to the {@code -f} path so the happy path succeeds. */
    private static final class WritingRunner implements SubprocessRunner {
        @Override
        public Result run(List<String> argv, String stdin, Duration timeout) throws IOException {
            int f = argv.indexOf("-f");
            Files.write(Path.of(argv.get(f + 1)), "RIFF....WAVE".getBytes());
            return new Result(0, "", "");
        }
    }

    /** A provider wired to an explicit config file + workspace root (both under {@code @TempDir}). */
    private static TtsToolProvider providerFor(Path home, Path workspace, SubprocessRunner runner) {
        TtsToolProvider provider = new TtsToolProvider();
        provider.config = new TtsConfig(home.resolve("tools").resolve("tts.json"));
        provider.runner = runner;
        provider.configuredWorkspaceRoot = Optional.of(workspace.toString());
        return provider;
    }

    private static Map<String, Object> args(String text, String voice) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (text != null) {
            map.put("text", text);
        }
        if (voice != null) {
            map.put("voice", voice);
        }
        return map;
    }

    private static void writeConfig(Path home, String json) throws IOException {
        Path file = home.resolve("tools").resolve("tts.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void specIsTtsSpeakWithoutConfirmation() {
        ToolSpec spec = TtsSpeakTool.SPEC;

        assertEquals("tts.speak", spec.name());
        assertEquals(PermissionScope.MEDIA_SYNTHESIZE, spec.requiredScope());
        assertEquals(false, spec.userConfirmRequired(),
                "tts.speak is fs-write-class → no user confirmation (unlike shell.exec)");
        assertTrue(spec.parametersJsonSchema().contains("\"text\""), "text is a declared parameter");
        assertTrue(spec.parametersJsonSchema().contains("\"required\":[\"text\"]"), "text is required");
    }

    @Test
    void toolsContributesOnlyTtsSpeak() {
        TtsToolProvider provider = new TtsToolProvider();

        assertEquals(List.of(TtsSpeakTool.SPEC), provider.tools());
        assertEquals("tts", provider.extensionId());
    }

    @Test
    void invokeSynthesizesAnAllowedCall(@TempDir Path home, @TempDir Path workspace) throws IOException {
        writeConfig(home, "{\"piperBin\":\"/opt/piper\",\"piperVoice\":\"/v/amy.onnx\"}");
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        String out = provider.invoke("tts.speak", args("hello", null));

        assertTrue(out.contains("tts/speech-"), "returns the workspace-relative path: " + out);
        try (var files = Files.list(workspace.resolve("tts"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".wav")),
                    "a WAV was written under <workspace>/tts/");
        }
    }

    @Test
    void invokeIsUnconfiguredWithNoConfigFile(@TempDir Path home, @TempDir Path workspace) {
        // No tools/tts.json written: the tool reports "not configured" (never a crash).
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        TtsException e = assertThrows(TtsException.class,
                () -> provider.invoke("tts.speak", args("hi", null)));
        assertTrue(e.getMessage().contains("not configured"), e.getMessage());
    }

    @Test
    void invokeRejectsMissingOrBlankText(@TempDir Path home, @TempDir Path workspace) throws IOException {
        writeConfig(home, "{\"piperBin\":\"/opt/piper\",\"piperVoice\":\"/v/amy.onnx\"}");
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        assertThrows(TtsException.class, () -> provider.invoke("tts.speak", args(null, null)));
        assertThrows(TtsException.class, () -> provider.invoke("tts.speak", args("   ", null)));
    }

    @Test
    void invokeUnknownToolThrows(@TempDir Path home, @TempDir Path workspace) {
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("tts.synth", args("hi", null)),
                "a name this provider does not contribute is a programming error");
    }

    // ---- #184 configGaps(): unconfigured (no piper) is a gap; a ready config is not ----

    @Test
    void configGapsFlagsTtsSpeakWhenUnconfigured(@TempDir Path home, @TempDir Path workspace) {
        // No tools/tts.json → not ready → tts.speak is a config gap naming the file + both fields.
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        Map<String, Object> gaps = Map.copyOf(provider.configGaps());
        assertTrue(gaps.containsKey("tts.speak"), () -> "an unconfigured tts is a gap; got " + gaps);
        assertTrue(gaps.get("tts.speak").toString().contains("piperBin"),
                () -> "the hint names piperBin; got " + gaps);
        assertTrue(gaps.get("tts.speak").toString().contains("piperVoice"),
                () -> "the hint names piperVoice; got " + gaps);
        assertTrue(gaps.get("tts.speak").toString().contains("tools/tts.json"),
                () -> "the hint names the file; got " + gaps);
    }

    @Test
    void configGapsIsEmptyWhenReady(@TempDir Path home, @TempDir Path workspace) throws IOException {
        writeConfig(home, "{\"piperBin\":\"/opt/piper\",\"piperVoice\":\"/v/amy.onnx\"}");
        TtsToolProvider provider = providerFor(home, workspace, new WritingRunner());

        assertTrue(provider.configGaps().isEmpty(), "a ready piper config means tts.speak is ready");
    }
}
