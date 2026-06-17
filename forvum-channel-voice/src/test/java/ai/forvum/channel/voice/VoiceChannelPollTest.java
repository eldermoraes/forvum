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
import java.util.concurrent.atomic.AtomicInteger;

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

    /** A VoicePipeline that always fails — to prove one bad scan cycle never kills the poll worker. */
    private static final class ThrowingPipeline extends VoicePipeline {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void process(Path audioFile, Spec spec) {
            attempts.incrementAndGet();
            throw new RuntimeException("simulated pipeline failure");
        }
    }

    private static VoiceChannel wired(Path home, VoicePipeline pipeline) {
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

    @Test
    void pollLoopSurvivesAThrowingScanAndKeepsPolling(@TempDir Path home) throws IOException {
        // A scan that throws (here: the pipeline fails on every file) must NOT kill the worker — pollLoop
        // catches it, logs, and runs the next cycle. Stop only on the SECOND sleep so the test PROVES the
        // loop survived the first throwing cycle and reached a second one. If pollLoop's catch were
        // removed, the first throw would escape pollLoop() and this test would error out (the
        // green-for-wrong-reason guard the [M4] lesson warns about).
        writeConfig(home, readyConfig(home));
        Path inbox = Files.createDirectories(home.resolve("inbox"));
        Files.writeString(inbox.resolve("hello.wav"), "x"); // never deleted: the throwing pipeline re-sees it

        ThrowingPipeline pipeline = new ThrowingPipeline();
        VoiceChannel channel = wired(home, pipeline);
        channel.running = true;
        AtomicInteger cycles = new AtomicInteger();
        channel.sleeper = millis -> {
            if (cycles.incrementAndGet() >= 2) {
                channel.running = false;
            }
        };

        channel.pollLoop(); // must NOT propagate the pipeline failure

        assertEquals(2, cycles.get(), "the loop survived the throwing first cycle and ran a second");
        assertTrue(pipeline.attempts.get() >= 2, "each surviving cycle re-attempted the failing file");
        assertFalse(channel.isPolling());
    }
}
