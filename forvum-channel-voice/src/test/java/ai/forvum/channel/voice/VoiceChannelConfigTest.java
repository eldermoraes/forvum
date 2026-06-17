package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.voice.VoiceChannelConfig.Spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code channels/voice.json} parsing and the {@link Spec} semantics: enabled-by-default, absent file
 * disabled, blank binaries treated as unset (warn + no-op upstream), inbox/outbox defaults + overrides,
 * the {@code allowedUserIds} allow-list (empty = any sender), and the {@link Spec#isReady()} readiness
 * gate.
 */
class VoiceChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    private static Spec parse(String raw, Path home) {
        return VoiceChannelConfig.parse(json(raw), home);
    }

    @Test
    void anAbsentFileIsDisabledAndNotReady(@TempDir Path home) {
        Spec spec = new VoiceChannelConfig(home).read();

        assertFalse(spec.enabled());
        assertFalse(spec.isReady());
        assertTrue(spec.whisperBin().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void aFullSpecParsesAndIsReady(@TempDir Path home) throws IOException {
        Path file = Files.createDirectories(home.resolve("channels")).resolve("voice.json");
        Files.writeString(file, """
                { "whisperBin": "/opt/whisper", "whisperModel": "/opt/ggml.bin",
                  "piperBin": "/opt/piper", "piperVoice": "/opt/voice.onnx",
                  "ffmpegPath": "/usr/bin/ffmpeg", "timeoutSeconds": 30,
                  "allowedUserIds": ["alice", "bob"] }
                """);

        Spec spec = new VoiceChannelConfig(home).read();

        assertTrue(spec.enabled(), "a channel is enabled unless \"enabled\": false");
        assertTrue(spec.isReady(), "all four binaries/models present => ready");
        assertEquals(Optional.of("/opt/whisper"), spec.whisperBin());
        assertEquals(Optional.of("/opt/ggml.bin"), spec.whisperModel());
        assertEquals(Optional.of("/opt/piper"), spec.piperBin());
        assertEquals(Optional.of("/opt/voice.onnx"), spec.piperVoice());
        assertEquals(Optional.of("/usr/bin/ffmpeg"), spec.ffmpegPath());
        assertEquals(30L, spec.timeoutSeconds());
        assertEquals(Set.of("alice", "bob"), spec.allowedUserIds());
    }

    @Test
    void inboxOutboxDefaultUnderChannelsVoice(@TempDir Path home) {
        Spec spec = parse("{}", home);

        assertEquals(home.resolve("channels/voice/inbox").normalize(), spec.inboxDir());
        assertEquals(home.resolve("channels/voice/outbox").normalize(), spec.outboxDir());
    }

    @Test
    void aRelativeInboxIsResolvedUnderHomeAndAnAbsoluteOneIsUsedAsIs(@TempDir Path home) {
        Spec rel = parse("{ \"inboxDir\": \"my-inbox\" }", home);
        assertEquals(home.resolve("my-inbox").normalize(), rel.inboxDir());

        Spec abs = parse("{ \"outboxDir\": \"/var/voice/out\" }", home);
        assertEquals(Path.of("/var/voice/out").normalize(), abs.outboxDir());
    }

    @Test
    void absentTimeoutDefaultsAndANonPositiveOneIsIgnored(@TempDir Path home) {
        assertEquals(VoiceChannelConfig.DEFAULT_TIMEOUT_SECONDS, parse("{}", home).timeoutSeconds());
        assertEquals(VoiceChannelConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{ \"timeoutSeconds\": 0 }", home).timeoutSeconds(), "a non-positive timeout is ignored");
        assertEquals(VoiceChannelConfig.DEFAULT_TIMEOUT_SECONDS,
                parse("{ \"timeoutSeconds\": -5 }", home).timeoutSeconds());
    }

    @Test
    void aMalformedFileThrowsForTheOperatorToSee(@TempDir Path home) throws IOException {
        Path file = Files.createDirectories(home.resolve("channels")).resolve("voice.json");
        Files.writeString(file, "{ not json");

        assertThrows(UncheckedIOException.class, () -> new VoiceChannelConfig(home).read(),
                "a malformed config is a real misconfiguration, not silently swallowed");
    }

    @Test
    void explicitFalseDisables(@TempDir Path home) {
        assertFalse(parse("{ \"enabled\": false }", home).enabled());
    }

    @Test
    void blankBinariesAreTreatedAsUnsetAndNotReady(@TempDir Path home) {
        Spec spec = parse(
                "{ \"whisperBin\": \"  \", \"whisperModel\": \"\", \"piperBin\": \"\", \"piperVoice\": \" \" }",
                home);

        assertTrue(spec.whisperBin().isEmpty());
        assertFalse(spec.isReady(), "blank binaries are unset, so the channel is not ready");
    }

    @Test
    void aPartiallyConfiguredChannelIsNotReady(@TempDir Path home) {
        // whisper present, piper missing => not ready (warn + no-op upstream).
        Spec spec = parse("{ \"whisperBin\": \"/w\", \"whisperModel\": \"/m\" }", home);
        assertFalse(spec.isReady());
    }

    @Test
    void blankAllowedIdsAreDropped(@TempDir Path home) {
        Spec spec = parse("{ \"allowedUserIds\": [\" alice \", \"\", \"  \"] }", home);
        assertEquals(Set.of("alice"), spec.allowedUserIds(), "ids are trimmed; blanks dropped");
    }

    @Test
    void anEmptyAllowListAllowsAnySender(@TempDir Path home) {
        Spec spec = parse("{}", home);

        assertTrue(spec.isSenderAllowed("voice-local"));
        assertTrue(spec.isSenderAllowed(null), "even an id-less sender (defensive)");
    }

    @Test
    void aNonEmptyAllowListRestrictsToListedIds(@TempDir Path home) {
        Spec spec = parse("{ \"allowedUserIds\": [\"alice\"] }", home);

        assertTrue(spec.isSenderAllowed("alice"));
        assertFalse(spec.isSenderAllowed("bob"));
        assertFalse(spec.isSenderAllowed(null));
    }

    @Test
    void resolveHomePrefersTheConfiguredHome() {
        assertEquals(Path.of("/custom/home").toAbsolutePath().normalize(),
                VoiceChannelConfig.resolveHome(Optional.of("/custom/home"), "/users/me"));
        assertEquals(Path.of("/users/me/.forvum").toAbsolutePath().normalize(),
                VoiceChannelConfig.resolveHome(Optional.empty(), "/users/me"));
        assertEquals(Path.of("/users/me/.forvum").toAbsolutePath().normalize(),
                VoiceChannelConfig.resolveHome(Optional.of("  "), "/users/me"),
                "a blank configured home falls back to <user.home>/.forvum");
    }
}
