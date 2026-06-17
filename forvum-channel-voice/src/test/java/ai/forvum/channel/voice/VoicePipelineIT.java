package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.voice.VoiceChannelConfig.Spec;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The module's CDI wiring and the full STT→turn→TTS pipeline (the P2-3 channel Verify), in one booted
 * {@code @QuarkusTest}: the real {@link VoicePipeline} + {@link DefaultSubprocessRunner} beans drive the
 * committed STUB whisper/piper scripts (no real binaries, no audio decoding) and the in-module
 * {@link FakeTurnDriver} (the engine's {@code TurnService} is banned by the Layer-3 enforcer). With
 * {@code forvum.home} pinned to a hermetic path with no {@code channels/voice.json} (test
 * {@code application.properties}) the {@link VoiceChannel} boots INERT: no worker, nothing thrown (the
 * no-config native smoke contract). Boots Quarkus in-JVM; runs under Surefire (headless library,
 * CLAUDE.md §4 exception). The real whisper/piper round-trip is a {@code @Tag("live")} concern.
 */
@QuarkusTest
class VoicePipelineIT {

    @Inject
    VoicePipeline pipeline;

    @Inject
    FakeTurnDriver driver;

    @Inject
    VoiceChannel channel;

    @Inject
    DefaultSubprocessRunner runner;

    @TempDir
    Path work;

    private Path whisperBin;
    private Path piperBin;

    @BeforeEach
    void setUp() throws IOException {
        driver.reset();
        whisperBin = installStub("stubs/whisper-stub.sh", "whisper-stub");
        piperBin = installStub("stubs/piper-stub.sh", "piper-stub");
    }

    /** A Spec pointing whisper/piper at the committed stubs, with the inbox/outbox under {@code work}. */
    private Spec spec(Set<String> allowed) {
        return spec(allowed, Optional.empty(), piperBin.toString());
    }

    private Spec spec(Set<String> allowed, Optional<String> ffmpeg, String piper) {
        return new Spec(true,
                Optional.of(whisperBin.toString()), Optional.of("/model/ggml.bin"),
                Optional.of(piper), Optional.of("/voice/en.onnx"),
                ffmpeg,
                work.resolve("inbox"), work.resolve("outbox"), allowed, 30);
    }

    private Path dropWav(String name) throws IOException {
        Path inbox = Files.createDirectories(work.resolve("inbox"));
        Path wav = inbox.resolve(name);
        Files.write(wav, new byte[] {'R', 'I', 'F', 'F'}); // a placeholder; the stub never decodes it
        return wav;
    }

    @Test
    void theBeansResolveAndTheNoConfigBootIsInert() {
        assertNotNull(pipeline);
        assertNotNull(runner);
        // forvum.home is pinned to a hermetic path with NO channels/voice.json, so the channel's onStart
        // observer already ran as a no-op: disabled, no worker, nothing thrown.
        assertFalse(channel.isPolling(), "an unconfigured channel never starts polling");
    }

    @Test
    void aDroppedWavIsTranscribedTurnedAndSynthesizedToTheOutbox() throws IOException {
        Path wav = dropWav("hello.wav");

        pipeline.process(wav, spec(Set.of())); // empty allow-list => any sender

        // The transcript (from the whisper stub) reached the turn as a voice ChannelMessage.
        assertEquals(1, driver.dispatched().size(), "the transcript must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("voice", dispatched.channelId());
        assertEquals("voice-local", dispatched.nativeUserId());
        assertEquals("transcript of hello.wav", dispatched.content());

        // The piper stub wrote the reply WAV into the outbox under the inbound stem.
        Path reply = work.resolve("outbox").resolve("hello.reply.wav");
        assertTrue(Files.isRegularFile(reply), "the synthesized reply WAV must land in the outbox");
        assertTrue(Files.size(reply) >= 44, "a non-empty (>=44-byte WAV header) reply was written");

        // The inbound file is consumed so it is not reprocessed.
        assertFalse(Files.exists(wav), "a processed inbound file is deleted");
    }

    @Test
    void theSenderIdIsTakenFromADoubleUnderscorePrefix() throws IOException {
        Path wav = dropWav("alice__hi.wav");

        pipeline.process(wav, spec(Set.of("alice")));

        assertEquals(1, driver.dispatched().size());
        assertEquals("alice", driver.dispatched().get(0).nativeUserId());
        assertTrue(Files.isRegularFile(work.resolve("outbox").resolve("alice__hi.reply.wav")));
    }

    @Test
    void aDisallowedSenderIsRefusedNoTurnNoReplyAndTheFileIsDropped() throws IOException {
        Path wav = dropWav("mallory__intrude.wav");

        pipeline.process(wav, spec(Set.of("alice")));

        assertTrue(driver.dispatched().isEmpty(), "a refused sender must NOT drive a turn");
        assertFalse(Files.exists(work.resolve("outbox").resolve("mallory__intrude.reply.wav")),
                "no reply is synthesized for a refused sender");
        assertFalse(Files.exists(wav), "the refused file is still consumed (not reprocessed)");
    }

    @Test
    void aNonWavWithoutFfmpegIsRejectedWithoutAReply() throws IOException {
        Path m4a = dropWav("clip.m4a"); // not a .wav, and the spec has no ffmpegPath

        pipeline.process(m4a, spec(Set.of()));

        assertTrue(driver.dispatched().isEmpty(), "a non-WAV with no ffmpeg cannot be transcribed");
        List<Path> outbox = VoiceChannel.listAudio(work.resolve("outbox"));
        assertTrue(outbox.isEmpty(), "no reply for an untranscodable input");
        assertFalse(Files.exists(m4a), "the bad file is still consumed so it is not reprocessed");
    }

    @Test
    void aNonWavIsTranscodedViaFfmpegThenTranscribedAndSynthesized() throws IOException {
        Path ffmpeg = installStub("stubs/ffmpeg-stub.sh", "ffmpeg-stub");
        Path m4a = dropWav("alice__clip.m4a");

        pipeline.process(m4a, spec(Set.of(), Optional.of(ffmpeg.toString()), piperBin.toString()));

        // The ffmpeg stub wrote a sibling .16k.wav which whisper transcribed; the turn ran.
        assertEquals(1, driver.dispatched().size(), "the transcoded WAV must drive a turn");
        assertEquals("transcript of alice__clip.16k.wav", driver.dispatched().get(0).content());
        assertTrue(Files.isRegularFile(work.resolve("outbox").resolve("alice__clip.reply.wav")));
        assertFalse(Files.exists(m4a), "the inbound file is consumed");
        assertFalse(Files.exists(work.resolve("inbox").resolve("alice__clip.16k.wav")),
                "the derived transcode temp is cleaned up");
    }

    @Test
    void aTtsFailureProducesNoOutboxFileButStillConsumesTheInput() throws IOException {
        Path failingPiper = installStub("stubs/piper-fail-stub.sh", "piper-fail-stub");
        Path wav = dropWav("hello.wav");

        pipeline.process(wav, spec(Set.of(), Optional.empty(), failingPiper.toString()));

        // STT + the turn ran (the transcript reached the driver), but TTS failed => no outbox file.
        assertEquals(1, driver.dispatched().size());
        assertFalse(Files.exists(work.resolve("outbox").resolve("hello.reply.wav")),
                "a failed TTS leaves no reply WAV (the half-written temp is removed)");
        assertTrue(VoiceChannel.listAudio(work.resolve("outbox")).isEmpty(),
                "no leftover temp files in the outbox");
        assertFalse(Files.exists(wav), "the inbound file is still consumed");
    }

    /** Copy a committed stub script to an executable file under {@code work}. */
    private Path installStub(String resource, String name) throws IOException {
        Path target = work.resolve(name);
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            Files.copy(in, target);
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(target, perms);
        return target;
    }
}
