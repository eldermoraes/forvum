package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The pure file-name helpers: the inbound sender id ({@link VoicePipeline#senderIdFrom}), the outbox
 * reply name ({@link VoicePipeline#replyFileName}), and the inbox listing's audio-candidate filter /
 * arrival-order sort ({@link VoiceChannel#listAudio} / {@link VoiceChannel#isAudioCandidate}).
 */
class VoiceChannelHelpersTest {

    @Test
    void senderIdDefaultsToVoiceLocalAndIsTakenFromADoubleUnderscorePrefix() {
        assertEquals("voice-local", VoicePipeline.senderIdFrom(Path.of("hello.wav")));
        assertEquals("voice-local", VoicePipeline.senderIdFrom(Path.of("clip.m4a")));
        assertEquals("alice", VoicePipeline.senderIdFrom(Path.of("alice__hello.wav")));
        assertEquals("voice-local", VoicePipeline.senderIdFrom(Path.of("__nouser.wav")),
                "a blank prefix falls back to the single-user default");
    }

    @Test
    void replyFileNameMirrorsTheInboundStem() {
        assertEquals("hello.reply.wav", VoicePipeline.replyFileName(Path.of("hello.wav")));
        assertEquals("alice__hello.reply.wav",
                VoicePipeline.replyFileName(Path.of("alice__hello.m4a")));
        assertEquals("noext.reply.wav", VoicePipeline.replyFileName(Path.of("noext")));
    }

    @Test
    void audioCandidateRejectsDerivedArtifactsAndDotfiles() {
        assertTrue(VoiceChannel.isAudioCandidate(Path.of("hello.wav")));
        assertTrue(VoiceChannel.isAudioCandidate(Path.of("clip.m4a")));
        assertFalse(VoiceChannel.isAudioCandidate(Path.of("hello.16k.wav")), "a derived transcode temp");
        assertFalse(VoiceChannel.isAudioCandidate(Path.of("hello.stt.txt")), "a derived transcript temp");
        assertFalse(VoiceChannel.isAudioCandidate(Path.of("voice-reply-1.wav.tmp")), "an in-flight temp");
        assertFalse(VoiceChannel.isAudioCandidate(Path.of(".DS_Store")), "a dotfile");
    }

    @Test
    void listAudioReturnsEmptyForAnAbsentInbox(@TempDir Path home) {
        assertEquals(List.of(), VoiceChannel.listAudio(home.resolve("nope")));
    }

    @Test
    void listAudioSortsByNameAndSkipsDerivedArtifacts(@TempDir Path inbox) throws IOException {
        Files.writeString(inbox.resolve("b.wav"), "x");
        Files.writeString(inbox.resolve("a.wav"), "x");
        Files.writeString(inbox.resolve("a.16k.wav"), "x"); // derived — must be skipped
        Files.writeString(inbox.resolve("a.stt.txt"), "x"); // derived — must be skipped
        Files.createDirectory(inbox.resolve("subdir"));      // not a regular file — must be skipped

        List<Path> audio = VoiceChannel.listAudio(inbox);

        assertEquals(List.of("a.wav", "b.wav"),
                audio.stream().map(p -> p.getFileName().toString()).toList(),
                "only inbound audio, oldest (name-sorted) first");
    }
}
