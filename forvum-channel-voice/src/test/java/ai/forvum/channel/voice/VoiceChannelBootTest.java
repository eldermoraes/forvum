package ai.forvum.channel.voice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code VoiceChannel.onStart} honors the graceful no-config boot contract (the M4/M17/P2-CH rule): an
 * absent {@code channels/voice.json} (disabled), an enabled-but-binary-less config, and a config missing
 * one of the four binaries all log + no-op — NO poll worker is started, nothing thrown — so the CI native
 * no-config smoke (no {@code ~/.forvum/}) boots and exits cleanly. A plain POJO test: the channel is wired
 * by hand (no CDI proxy), and the only side effect under test is whether a worker executor was started.
 */
class VoiceChannelBootTest {

    /** Wire a channel pointing at an explicit home (no CDI). The pipeline stays null on purpose: the
     *  disabled/not-ready branches must return BEFORE ever touching it. */
    private static VoiceChannel wiredChannel(Path home) {
        VoiceChannel channel = new VoiceChannel();
        channel.config = new VoiceChannelConfig(home);
        return channel;
    }

    @Test
    void absentConfigLeavesTheChannelInert(@TempDir Path home) {
        VoiceChannel channel = wiredChannel(home); // no channels/voice.json under this home

        channel.onStart(null);

        assertNull(channel.worker, "a disabled channel never starts the poll worker");
        assertFalse(channel.isPolling());
        channel.onStop(null); // safe even though no worker was started
    }

    @Test
    void enabledButBinaryLessConfigLeavesTheChannelInert(@TempDir Path home) throws IOException {
        write(home, "{ \"enabled\": true }");
        VoiceChannel channel = wiredChannel(home);

        channel.onStart(null);

        assertNull(channel.worker, "no binaries => warn + no-op");
        channel.onStop(null);
    }

    @Test
    void aPartiallyConfiguredConfigLeavesTheChannelInert(@TempDir Path home) throws IOException {
        // whisper present, piper missing => not ready => no worker.
        write(home, "{ \"whisperBin\": \"/w\", \"whisperModel\": \"/m\" }");
        VoiceChannel channel = wiredChannel(home);

        channel.onStart(null);

        assertNull(channel.worker, "a half-configured channel must not start the worker");
        channel.onStop(null);
    }

    @Test
    void aReadyConfigStartsThePollWorker(@TempDir Path home) throws IOException {
        // The complement of the inert tests: a fully-configured (isReady) channel must actually START the
        // worker — exercising onStart's started path (createDirectories + worker.submit) those tests never
        // reach. The inbox stays empty so the worker's first scan finds no files and never touches the
        // (null) pipeline; the worker is stopped in the finally.
        Path inbox = home.resolve("box-in");
        write(home, "{ \"whisperBin\": \"/w\", \"whisperModel\": \"/m\", \"piperBin\": \"/p\","
                + " \"piperVoice\": \"/v\", \"inboxDir\": \"" + inbox + "\","
                + " \"outboxDir\": \"" + home.resolve("box-out") + "\" }");
        VoiceChannel channel = wiredChannel(home);

        try {
            channel.onStart(null);

            assertNotNull(channel.worker, "a ready channel starts the poll worker");
            assertTrue(channel.isPolling(), "the worker loop is running");
            assertTrue(Files.isDirectory(inbox), "onStart created the inbox directory");
        } finally {
            channel.onStop(null); // stop the real virtual-thread worker
        }
    }

    private static void write(Path home, String json) throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("voice.json"), json);
    }
}
