package ai.forvum.channel.voice;

import ai.forvum.channel.voice.VoiceChannelConfig.Spec;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates one inbound audio file into a spoken reply (P2-3 / #28): ensure the audio is whisper's
 * required 16 kHz mono 16-bit WAV (transcode via an optional ffmpeg, else require a conformant
 * {@code .wav}), transcribe it (STT via whisper.cpp), drive a turn through the SDK
 * {@link ChannelTurnDriver}, accumulate the reply text via the byte-identical {@link #render}, synthesize
 * it (TTS via piper), and move the result WAV atomically into the outbox. All audio decode/encode happens
 * in the EXTERNAL whisper/piper/ffmpeg processes (driven through {@link SubprocessRunner}); the JVM links
 * no audio codec.
 *
 * <p><strong>Logging discipline.</strong> The transcript and the reply text are sensitive (the Signal
 * lesson): they are NEVER logged — only file names and lengths.
 */
@ApplicationScoped
public class VoicePipeline {

    private static final Logger LOG = Logger.getLogger(VoicePipeline.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "voice";

    @Inject
    ChannelTurnDriver turns;

    @Inject
    SubprocessRunner runner;

    /**
     * Process one inbound audio file end to end. Each step is guarded so a single bad file (a missing
     * binary, a failed transcode, an empty transcript) is logged and skipped without killing the polling
     * worker; the file is deleted on success (and on a terminal handling failure) so it is not reprocessed
     * on the next cycle.
     *
     * @param audioFile the inbound audio file (already in the inbox)
     * @param spec      the resolved channel config (binaries, timeout, outbox, allow-list)
     */
    public void process(Path audioFile, Spec spec) {
        String senderId = senderIdFrom(audioFile);
        if (!spec.isSenderAllowed(senderId)) {
            LOG.warnf("Voice: refused an unauthorized sender (not in allowedUserIds; %d authorized id(s));"
                    + " dropping %s.", spec.allowedUserIds().size(), audioFile.getFileName());
            deleteQuietly(audioFile);
            return;
        }

        Duration timeout = Duration.ofSeconds(spec.timeoutSeconds());
        Path wav = null;
        boolean wavIsDerived = false;
        try {
            wav = ensureWav(audioFile, spec, timeout);
            wavIsDerived = !wav.equals(audioFile);

            String transcript = transcribe(wav, spec, timeout);
            if (transcript == null || transcript.isBlank()) {
                LOG.warnf("Voice: STT produced no transcript for %s; no turn dispatched.",
                        audioFile.getFileName());
                return;
            }

            String reply = runTurn(senderId, transcript);
            if (reply == null || reply.isEmpty()) {
                LOG.infof("Voice: the turn produced no reply text for %s; nothing to synthesize.",
                        audioFile.getFileName());
                return;
            }

            Path out = synthesize(reply, spec, timeout, audioFile);
            if (out != null) {
                LOG.infof("Voice: wrote a %d-char reply to %s.", reply.length(), out.getFileName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Voice: failed to process %s (%s); dropping it.",
                    audioFile.getFileName(), e.getMessage());
        } finally {
            // Always consume the inbound file so it is not reprocessed; remove a derived temp WAV too.
            deleteQuietly(audioFile);
            if (wavIsDerived) {
                deleteQuietly(wav);
            }
        }
    }

    /**
     * Ensure {@code audioFile} is a 16 kHz mono 16-bit WAV. A {@code .wav} is assumed conformant and used
     * as-is (the operator's contract); any other extension requires a configured {@code ffmpegPath} to
     * transcode into a sibling temp {@code .16k.wav}, else the file is rejected. The derived WAV (when
     * any) is the second return; the caller deletes it.
     */
    Path ensureWav(Path audioFile, Spec spec, Duration timeout)
            throws IOException, InterruptedException {
        String name = audioFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav")) {
            return audioFile;
        }
        if (spec.ffmpegPath().isEmpty()) {
            throw new IOException("non-WAV audio and no ffmpegPath configured (drop a 16 kHz mono WAV, or "
                    + "set ffmpegPath in channels/voice.json)");
        }
        Path out = siblingTemp(audioFile, ".16k.wav");
        SubprocessRunner.Result result = runner.run(
                List.of(spec.ffmpegPath().get(), "-y", "-i", audioFile.toString(),
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", out.toString()),
                null, timeout);
        if (!result.ok()) {
            throw new IOException("ffmpeg transcode failed (exit " + result.exitCode() + ")");
        }
        return out;
    }

    /**
     * Transcribe a conformant WAV to text via whisper.cpp:
     * {@code [whisperBin, -m, model, -f, wav, -nt, -otxt, -of, <stem>]}. {@code -nt} suppresses
     * timestamps; {@code -otxt -of <stem>} writes {@code <stem>.txt}, which is read back and deleted (the
     * stdout banner is noisy, so the file is the reliable transcript source). Falls back to stdout if no
     * text file was produced.
     */
    String transcribe(Path wav, Spec spec, Duration timeout) throws IOException, InterruptedException {
        Path stem = siblingTemp(wav, ".stt");
        Path txt = wav.resolveSibling(stem.getFileName() + ".txt");
        try {
            SubprocessRunner.Result result = runner.run(
                    List.of(spec.whisperBin().get(), "-m", spec.whisperModel().get(),
                            "-f", wav.toString(), "-nt", "-otxt", "-of", stem.toString()),
                    null, timeout);
            if (!result.ok()) {
                throw new IOException("whisper STT failed (exit " + result.exitCode() + ")");
            }
            if (Files.isRegularFile(txt)) {
                return Files.readString(txt).strip();
            }
            return result.stdout().strip();
        } finally {
            deleteQuietly(txt);
        }
    }

    /**
     * Drive one turn for {@code transcript} and accumulate the reply text from its {@link AgentEvent}
     * stream via {@link #render}. There is no outbound channel-send API on the SPI; the channel collects
     * the reply itself (mirroring the other channels' processors).
     */
    String runTurn(String senderId, String transcript) {
        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, senderId, transcript, Instant.now());
        StringBuilder reply = new StringBuilder();
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                reply.append(rendered);
            }
        });
        return reply.toString();
    }

    /**
     * Synthesize {@code reply} to speech via piper ({@code echo TEXT | [piperBin, -m, voice, -f, out]}),
     * writing to a sibling temp WAV then moving it ATOMICALLY into the outbox so a consumer never sees a
     * half-written file. The outbox file name mirrors the inbound stem (so the operator can correlate the
     * reply with its prompt). Returns the final outbox path, or {@code null} on a TTS failure.
     */
    Path synthesize(String reply, Spec spec, Duration timeout, Path audioFile)
            throws IOException, InterruptedException {
        Files.createDirectories(spec.outboxDir());
        Path temp = Files.createTempFile(spec.outboxDir(), "voice-reply-", ".wav.tmp");
        SubprocessRunner.Result result = runner.run(
                List.of(spec.piperBin().get(), "-m", spec.piperVoice().get(), "-f", temp.toString()),
                reply, timeout);
        if (!result.ok()) {
            deleteQuietly(temp);
            LOG.warnf("Voice: piper TTS failed (exit %d) for a %d-char reply.",
                    result.exitCode(), reply.length());
            return null;
        }
        Path out = spec.outboxDir().resolve(replyFileName(audioFile));
        try {
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // ATOMIC_MOVE is unsupported on some filesystems; fall back to a plain replace.
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /** The outbox reply file name: the inbound stem + {@code .reply.wav}. */
    static String replyFileName(Path audioFile) {
        String name = audioFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + ".reply.wav";
    }

    /**
     * The native user id for an inbound file: its stem before the first {@code '.'} or {@code '__'}
     * separator, or the single-user default {@code "voice-local"} when the stem is empty. This lets an
     * operator address a specific user via the file name (e.g. {@code alice__hello.wav}) while keeping the
     * single-user drop-a-clip convenience.
     */
    static String senderIdFrom(Path audioFile) {
        String name = audioFile.getFileName().toString();
        int sep = name.indexOf("__");
        String id = sep > 0 ? name.substring(0, sep) : "";
        if (id.isBlank()) {
            return "voice-local";
        }
        return id;
    }

    /** A sibling temp path next to {@code file} with the given suffix replacing its extension. */
    private static Path siblingTemp(Path file, String suffix) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return file.resolveSibling(stem + suffix);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; a stuck file is logged on the next cycle, never fatal.
        }
    }

    /**
     * Render an {@link AgentEvent} to the reply text, or an empty string to contribute nothing.
     * Exhaustive over the sealed event type (no {@code default} branch), mirroring the Signal/Telegram/
     * Discord/web channels' {@code render}: v0.1 (streaming Option B) emits only the {@link TokenDelta}
     * reply (the terminal {@link Done} repeats it, so it is skipped) and an {@link ErrorEvent}'s message;
     * the tool-lifecycle events are not surfaced. Package-private so {@code VoiceRenderTest} covers every
     * arm.
     */
    // Intentionally byte-identical to forvum-channel-signal's EnvelopeProcessor.render,
    // forvum-channel-telegram's UpdateProcessor.render, forvum-channel-discord's MessageProcessor.render,
    // and forvum-channel-web's ChatSocket.render (module isolation forbids a shared type). The
    // exhaustive, default-less switch makes a new AgentEvent arm a compile error in EVERY channel, so the
    // renderers cannot silently drift.
    static String render(AgentEvent event) {
        return switch (event) {
            case TokenDelta delta -> delta.text();
            case ErrorEvent error -> error.message();
            case Done ignored -> "";
            case ToolInvoked ignored -> "";
            case ToolResult ignored -> "";
            case FallbackTriggered ignored -> "";
        };
    }
}
