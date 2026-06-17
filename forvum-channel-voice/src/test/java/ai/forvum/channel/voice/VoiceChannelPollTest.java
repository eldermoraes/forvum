package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.voice.VoiceChannelConfig.Spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The poll worker's loop and one-scan semantics ({@link VoiceChannel#pollOnce} / {@link
 * VoiceChannel#pollLoop}), driven as a plain POJO (no CDI, no real subprocess): a recording fake
 * {@link VoicePipeline} captures the files handed to it, and a one-shot {@link VoiceChannel.Sleeper} stops
 * the loop after a single cycle so the blocking loop is asserted deterministically without real waits.
 */
class VoiceChannelPollTest {

    /** A VoicePipeline that records the files it is asked to process and deletes them (like the real one). */
    private static final class RecordingPipeline extends VoicePipeline {
        final CopyOnWriteArrayList<String> processed = new CopyOnWriteArrayList<>();

        @Override
        public void process(Path audioFile, Spec spec) {
            processed.add(audioFile.getFileName().toString());
            try {
                Files.deleteIfExists(audioFile);
            } catch (IOException ignored) {
                // best effort, mirrors the real pipeline
            }
        }
    }

    private static VoiceChannel wired(Path home, RecordingPipeline pipeline) {
        VoiceChannel channel = new VoiceChannel();
        channel.config = new VoiceChannelConfig(home);
        channel.pipeline = pipeline;
        return channel;
    }

    private static void writeConfig(Path home, String json) throws IOException {
        Files.writeString(Files.createDirectories(home.resolve("channels")).resolve("voice.json"), json);
    }

    private static String readyConfig(Path home) {
        return "{ \"whisperBin\": \"/w\", \"whisperModel\": \"/m\", \"piperBin\": \"/p\","
                + " \"piperVoice\": \"/v\", \"inboxDir\": \"" + home.resolve("inbox") + "\" }";
    }

    @Test
    void pollOnceHandsEveryInboxAudioFileToThePipeline(@TempDir Path home) throws IOException {
        writeConfig(home, readyConfig(home));
        Path inbox = Files.createDirectories(home.resolve("inbox"));
        Files.writeString(inbox.resolve("a.wav"), "x");
        Files.writeString(inbox.resolve("b.wav"), "x");
        Files.writeString(inbox.resolve("a.16k.wav"), "x"); // derived — must be skipped

        RecordingPipeline pipeline = new RecordingPipeline();
        VoiceChannel channel = wired(home, pipeline);

        channel.pollOnce();

        assertEquals(List.of("a.wav", "b.wav"), pipeline.processed,
                "only inbound audio, in arrival (name) order");
    }

    @Test
    void pollOnceIsAnInertNoOpWhenTheChannelIsNotReady(@TempDir Path home) throws IOException {
        writeConfig(home, "{ \"enabled\": true }"); // no binaries => not ready
        Path inbox = Files.createDirectories(home.resolve("inbox"));
        Files.writeString(inbox.resolve("a.wav"), "x");

        RecordingPipeline pipeline = new RecordingPipeline();
        wired(home, pipeline).pollOnce();

        assertTrue(pipeline.processed.isEmpty(), "a not-ready channel scans nothing");
    }

    @Test
    void pollLoopProcessesThenStopsAfterOneCycle(@TempDir Path home) throws IOException {
        writeConfig(home, readyConfig(home));
        Path inbox = Files.createDirectories(home.resolve("inbox"));
        Files.writeString(inbox.resolve("hello.wav"), "x");

        RecordingPipeline pipeline = new RecordingPipeline();
        VoiceChannel channel = wired(home, pipeline);
        channel.running = true;
        // The first sleep ends the loop (one full scan ran before it), so no real waiting occurs.
        channel.sleeper = millis -> channel.running = false;

        channel.pollLoop();

        assertEquals(List.of("hello.wav"), pipeline.processed, "the single inbox file was processed once");
        assertFalse(channel.isPolling(), "the loop exited after the sleeper stopped it");
    }
}
