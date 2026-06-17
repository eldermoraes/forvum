package ai.forvum.channel.voice;

import ai.forvum.channel.voice.VoiceChannelConfig.Spec;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Voice channel's inbound surface (P2-3 / #28): a worker on a virtual thread that polls
 * {@code $FORVUM_HOME/channels/voice/inbox/} for audio files and hands each one to {@link VoicePipeline}
 * (whisper STT → turn → piper TTS → outbox), repeating until shutdown. macOS {@code WatchService} latency
 * (Risk #7) makes a deterministic polling loop the right choice and matches the channel-loop idiom
 * (Signal/Telegram). Per CLAUDE.md §3.8 the loop is BLOCKING on a virtual thread (no Mutiny/reactive);
 * {@code @RunOnVirtualThread} applies only to externally-invoked callbacks, so a loop this bean starts
 * itself is placed on a virtual thread via {@link Executors#newVirtualThreadPerTaskExecutor()}.
 *
 * <p><strong>File-drop transport.</strong> v0.1 voice is a file-drop channel, NOT a live microphone: an
 * operator drops a recorded clip in the inbox and gets a synthesized reply WAV in the outbox. Live
 * capture/playback (and barge-in) is a documented follow-up — it would pull {@code javax.sound.sampled}
 * or a live-capture binary, a much larger native + cross-platform surface that this design deliberately
 * avoids.
 *
 * <p><strong>Absent config → warn + no-op.</strong> If {@code channels/voice.json} is absent, the channel
 * is disabled, or the whisper/piper binaries are not configured, the loop is NOT started and the bean
 * logs and returns — it never throws and never blocks. This keeps the CI native no-config boot (no
 * {@code ~/.forvum/}) graceful, the same contract the M4 watcher and the other channels honor.
 */
@ApplicationScoped
public class VoiceChannel {

    private static final Logger LOG = Logger.getLogger(VoiceChannel.class);

    /** How long the worker sleeps between inbox scans. */
    static final long POLL_INTERVAL_MILLIS = 1000;

    @Inject
    VoiceChannelConfig config;

    @Inject
    VoicePipeline pipeline;

    /** Package-private so tests can start/stop a deterministic cycle and assert inert boots. */
    volatile boolean running;
    /** Package-private so the boot test can assert no worker was started on an inert boot. */
    ExecutorService worker;
    /** Sleep seam (default {@link Thread#sleep}); a test substitutes a no-op/recording sleeper. */
    Sleeper sleeper = Thread::sleep;

    /** An interruptible sleep, abstracted so a loop test asserts cycles without real waits. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    void onStart(@Observes StartupEvent event) {
        Spec spec = config.read();
        if (!spec.enabled()) {
            LOG.info("Voice channel disabled (no channels/voice.json, or \"enabled\": false); not polling "
                    + "for audio.");
            return;
        }
        if (!spec.isReady()) {
            LOG.warn("Voice channel enabled but whisperBin/whisperModel/piperBin/piperVoice missing in "
                    + "channels/voice.json; not polling. Configure the operator-installed whisper.cpp and "
                    + "piper binaries to activate the channel.");
            return;
        }
        try {
            Files.createDirectories(spec.inboxDir());
            Files.createDirectories(spec.outboxDir());
        } catch (IOException e) {
            LOG.warnf("Voice channel: cannot create inbox/outbox under %s (%s); not polling.",
                    spec.inboxDir(), e.getMessage());
            return;
        }
        running = true;
        worker = Executors.newVirtualThreadPerTaskExecutor();
        worker.submit(this::pollLoop);
        LOG.infof("Voice channel started: polling %s for audio on a virtual thread (file-drop transport; "
                + "drop a 16 kHz mono WAV, get a reply WAV in %s).", spec.inboxDir(), spec.outboxDir());
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    /**
     * Whether the poll worker is running. A METHOD (not field access) so a {@code @QuarkusTest} can
     * assert the inert no-config boot through the CDI client proxy (field reads on a proxy see the
     * proxy's own field, never the contextual instance's).
     */
    boolean isPolling() {
        return running;
    }

    /**
     * The poll loop: scan the inbox, process each new file, sleep, repeat until {@link #onStop}. Each
     * scan re-reads the {@link Spec} so an operator's edit (binaries, allow-list, timeout) takes effect on
     * the next cycle without a restart. A scan failure is logged and the loop continues (one bad cycle
     * never kills the worker).
     */
    void pollLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (RuntimeException e) {
                LOG.warnf("Voice: an inbox scan failed (%s); continuing.", e.getMessage());
            }
            if (!running) {
                return;
            }
            try {
                sleeper.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // shutdownNow during the sleep — exit quietly.
            }
        }
    }

    /**
     * One inbox scan: re-read the config, list the audio files currently in the inbox, and process each
     * through {@link VoicePipeline} (which deletes a file once handled). Package-private so a
     * {@code @QuarkusTest} can drive a single deterministic cycle without the timing of the real loop.
     */
    void pollOnce() {
        Spec spec = config.read();
        if (!spec.enabled() || !spec.isReady()) {
            return;
        }
        for (Path file : listAudio(spec.inboxDir())) {
            pipeline.process(file, spec);
        }
    }

    /**
     * List the regular files in {@code inbox} that look like audio (any extension, but skip the channel's
     * own derived temp artifacts {@code .16k.wav}/{@code .stt.txt}/{@code .tmp}), oldest first so files
     * are processed in arrival order. Returns an empty list when the inbox is absent/unreadable.
     */
    static List<Path> listAudio(Path inbox) {
        if (!Files.isDirectory(inbox)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(inbox)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && isAudioCandidate(p)) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan the voice inbox " + inbox + ".", e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /** Whether {@code file} is an inbound audio candidate (not one of the channel's derived artifacts). */
    static boolean isAudioCandidate(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.endsWith(".16k.wav") && !name.endsWith(".stt.txt") && !name.endsWith(".tmp")
                && !name.startsWith(".");
    }
}
