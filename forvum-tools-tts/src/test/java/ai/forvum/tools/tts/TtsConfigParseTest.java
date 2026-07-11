package ai.forvum.tools.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Parsing of {@code tools/tts.json} into a {@link TtsConfig.Spec} (mirrors {@code ShellAllowlistParseTest}):
 * the piper keys, the optional {@code voices} map, {@code timeoutSeconds} defaulting, the unconfigured
 * (absent/non-object) shapes, and the {@code isReady}/{@code resolveVoice} contract.
 */
class TtsConfigParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TtsConfig.Spec parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            return TtsConfig.parse(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesPiperBinAndDefaultVoice() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/opt/piper\",\"piperVoice\":\"/v/amy.onnx\"}");

        assertEquals("/opt/piper", spec.piperBin().orElseThrow());
        assertEquals("/v/amy.onnx", spec.piperVoice().orElseThrow());
        assertTrue(spec.isReady(), "both piperBin and piperVoice present → ready");
        assertEquals(TtsConfig.DEFAULT_TIMEOUT_SECONDS, spec.timeoutSeconds());
    }

    @Test
    void parsesTheOptionalVoicesMapSkippingBlankEntries() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v/def.onnx\",\"voices\":{"
                + "\"amy\":\"/v/amy.onnx\",\"ryan\":\"/v/ryan.onnx\",\"blank\":\"  \",\"nul\":null}}");

        assertEquals(Map.of("amy", "/v/amy.onnx", "ryan", "/v/ryan.onnx"), spec.voices(),
                "the named voices are parsed; blank/null entries are skipped");
    }

    @Test
    void absentVoicesMapYieldsAnEmptyMap() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v/def.onnx\"}");

        assertTrue(spec.voices().isEmpty());
    }

    @Test
    void blankPiperBinOrVoiceIsAbsentSoNotReady() {
        assertFalse(parse("{\"piperBin\":\"  \",\"piperVoice\":\"/v.onnx\"}").isReady());
        assertFalse(parse("{\"piperBin\":\"/p\",\"piperVoice\":\"\"}").isReady());
        assertFalse(parse("{\"piperVoice\":\"/v.onnx\"}").isReady(), "no piperBin → not ready");
        assertFalse(parse("{\"piperBin\":\"/p\"}").isReady(), "no piperVoice → not ready");
    }

    @Test
    void timeoutDefaultsWhenAbsentNonNumericOrNonPositive() {
        assertEquals(TtsConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v\"}").timeoutSeconds());
        assertEquals(TtsConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v\",\"timeoutSeconds\":\"nope\"}").timeoutSeconds());
        assertEquals(TtsConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v\",\"timeoutSeconds\":0}").timeoutSeconds());
        assertEquals(TtsConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v\",\"timeoutSeconds\":-5}").timeoutSeconds());
    }

    @Test
    void usesAConfiguredPositiveTimeout() {
        assertEquals(30,
                parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v\",\"timeoutSeconds\":30}").timeoutSeconds());
    }

    @Test
    void nonObjectOrNullRootIsUnconfigured() {
        assertFalse(parse("[1,2,3]").isReady());
        assertFalse(parse("\"a string\"").isReady());
        assertFalse(parse("null").isReady());
        assertFalse(parse("{}").isReady(), "an empty object is unconfigured");
    }

    @Test
    void resolveVoiceReturnsTheDefaultForBlankOrNullName() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v/def.onnx\"}");

        assertEquals("/v/def.onnx", spec.resolveVoice(null));
        assertEquals("/v/def.onnx", spec.resolveVoice(""));
        assertEquals("/v/def.onnx", spec.resolveVoice("   "));
    }

    @Test
    void resolveVoiceReturnsANamedVoiceFromTheMap() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v/def.onnx\",\"voices\":{"
                + "\"amy\":\"/v/amy.onnx\"}}");

        assertEquals("/v/amy.onnx", spec.resolveVoice("amy"));
        assertEquals("/v/amy.onnx", spec.resolveVoice("  amy  "), "the name is trimmed");
    }

    @Test
    void resolveVoiceRejectsAnUnknownName() {
        TtsConfig.Spec spec = parse("{\"piperBin\":\"/p\",\"piperVoice\":\"/v/def.onnx\",\"voices\":{"
                + "\"amy\":\"/v/amy.onnx\"}}");

        TtsException e = assertThrows(TtsException.class, () -> spec.resolveVoice("bogus"));
        assertTrue(e.getMessage().contains("amy"), "the error lists the configured voice names");
        assertTrue(e.getMessage().contains("bogus"));
    }

    @Test
    void readReturnsUnconfiguredWhenTheFileIsAbsent(@TempDir Path home) {
        TtsConfig config = new TtsConfig(home.resolve("tools").resolve("tts.json"));

        assertFalse(config.read().isReady(),
                "with no tools/tts.json the tool is unconfigured (the no-config smoke)");
    }

    @Test
    void readParsesAPresentFile(@TempDir Path home) throws IOException {
        Path file = home.resolve("tools").resolve("tts.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"piperBin\":\"/opt/piper\",\"piperVoice\":\"/v/amy.onnx\"}");
        TtsConfig config = new TtsConfig(file);

        assertTrue(config.read().isReady());
    }

    @Test
    void readThrowsUncheckedIoOnAMalformedFile(@TempDir Path home) throws IOException {
        Path file = home.resolve("tools").resolve("tts.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");
        TtsConfig config = new TtsConfig(file);

        UncheckedIOException e = assertThrows(UncheckedIOException.class, config::read);
        assertTrue(e.getMessage().contains("tts.json"), "the error names the offending file");
    }
}
