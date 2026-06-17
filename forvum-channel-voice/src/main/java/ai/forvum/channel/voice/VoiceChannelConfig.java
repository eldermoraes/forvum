package ai.forvum.channel.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the Voice channel's file-based configuration from {@code $FORVUM_HOME/channels/voice.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables Voice, points the channel at
 * their OPERATOR-installed whisper.cpp ({@code whisperBin} + {@code whisperModel}) and piper
 * ({@code piperBin} + {@code piperVoice}) binaries, optionally configures an {@code ffmpegPath} for
 * transcoding, overrides {@code inboxDir}/{@code outboxDir}, restricts {@code allowedUserIds}, and tunes
 * {@code timeoutSeconds} by editing one file, no recompile. Mirrors {@code SignalChannelConfig}.
 *
 * <p>The engine's {@code ChannelReader} (which reads this same tree) lives in {@code forvum-engine},
 * which a Layer-3 channel must not depend on (the module enforcer). So this reader resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from the
 * {@code FORVUM_HOME} env var), falling back to {@code <user.home>/.forvum} — and reads the JSON directly
 * with Jackson. With no {@code ~/.forvum/} the file is absent and {@link #read()} returns
 * {@link Spec#empty()} (disabled, no binaries), so the channel boots gracefully in the CI native
 * no-config smoke.
 *
 * <p>The config is read on demand (each {@link #read()} re-reads the file) so an operator's edit takes
 * effect on the next poll cycle without a restart — consistent with the WatchService hot-reload model,
 * without this module needing the engine's watcher.
 */
@ApplicationScoped
public class VoiceChannelConfig {

    static final String CHANNEL_ID = "voice";
    static final String DEFAULT_HOME_DIR = ".forvum";
    /** The default inbox/outbox live under {@code channels/voice/} relative to the home. */
    static final String DEFAULT_INBOX = "channels/voice/inbox";
    static final String DEFAULT_OUTBOX = "channels/voice/outbox";
    /** Default per-subprocess timeout when the operator does not set {@code timeoutSeconds}. */
    static final long DEFAULT_TIMEOUT_SECONDS = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path home;
    private final Path configFile;

    @Inject
    public VoiceChannelConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        this.home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /** Package-private constructor binding an explicit home directory — for tests. */
    VoiceChannelConfig(Path home) {
        this.home = home.toAbsolutePath().normalize();
        this.configFile = this.home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /**
     * Pure home resolution, mirroring {@code ForvumHome.resolve}: the configured home when present and
     * non-blank, otherwise {@code <userHome>/.forvum}. Always absolute and normalized.
     */
    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /** The resolved Forvum home directory backing this channel's inbox/outbox defaults. */
    Path home() {
        return home;
    }

    /**
     * The current spec read from {@code channels/voice.json}. Returns {@link Spec#empty()} if the file is
     * absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real misconfiguration
     * the operator must see, not silently swallowed).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Voice channel config " + configFile + ".", e);
        }
        return parse(root, home);
    }

    /**
     * Parse a {@code channels/voice.json} JSON tree into a {@link Spec}, resolving the inbox/outbox
     * directories against {@code home}. Package-private for tests.
     */
    static Spec parse(JsonNode root, Path home) {
        if (root == null || root.isNull()) {
            return Spec.empty();
        }
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode == null || enabledNode.asBoolean(true);

        Set<String> allowed = new LinkedHashSet<>();
        JsonNode allowedNode = root.get("allowedUserIds");
        if (allowedNode != null && allowedNode.isArray()) {
            for (JsonNode id : allowedNode) {
                String value = id.asText().trim();
                if (!value.isEmpty()) {
                    allowed.add(value);
                }
            }
        }

        Path inbox = resolveDir(root, "inboxDir", home, DEFAULT_INBOX);
        Path outbox = resolveDir(root, "outboxDir", home, DEFAULT_OUTBOX);

        long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        JsonNode timeoutNode = root.get("timeoutSeconds");
        if (timeoutNode != null && timeoutNode.isNumber() && timeoutNode.asLong() > 0) {
            timeoutSeconds = timeoutNode.asLong();
        }

        return new Spec(enabled,
                nonBlank(root, "whisperBin"), nonBlank(root, "whisperModel"),
                nonBlank(root, "piperBin"), nonBlank(root, "piperVoice"),
                nonBlank(root, "ffmpegPath"),
                inbox, outbox, Set.copyOf(allowed), timeoutSeconds);
    }

    /**
     * Resolve a directory config key against {@code home}: an absolute configured path is used as-is, a
     * relative one is resolved under {@code home}, and an absent/blank value falls back to
     * {@code home/<defaultRelative>}. Always normalized.
     */
    private static Path resolveDir(JsonNode root, String field, Path home, String defaultRelative) {
        JsonNode node = root.get(field);
        String configured = node == null ? null : node.asText();
        if (configured == null || configured.isBlank()) {
            return home.resolve(defaultRelative).normalize();
        }
        Path path = Path.of(configured.strip());
        return (path.isAbsolute() ? path : home.resolve(path)).normalize();
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText().strip());
    }

    /**
     * The Voice channel's resolved configuration. Parsed by hand from the JSON tree (no reflective
     * Jackson binding into the record), exactly like {@code SignalChannelConfig.Spec} — so, like that
     * record, it needs no {@code @RegisterForReflection}: the native image never (de)serializes it
     * reflectively.
     *
     * @param enabled        whether the channel is enabled (enabled unless {@code "enabled": false}); an
     *                       absent file is treated as disabled by {@link Spec#empty()}.
     * @param whisperBin     the operator-installed whisper.cpp binary path (STT), absent when unset
     *                       (channel must warn + no-op, never crash).
     * @param whisperModel   the whisper GGML model path passed via {@code -m}, absent when unset.
     * @param piperBin       the operator-installed piper binary path (TTS), absent when unset.
     * @param piperVoice     the piper ONNX voice model passed via {@code -m}, absent when unset.
     * @param ffmpegPath     an OPTIONAL ffmpeg binary path for transcoding non-WAV / non-conformant audio
     *                       to whisper's required 16 kHz mono 16-bit WAV; absent means the operator must
     *                       drop a conformant {@code .wav}.
     * @param inboxDir       the directory polled for inbound audio files (default
     *                       {@code home/channels/voice/inbox}).
     * @param outboxDir      the directory the synthesized reply WAV is moved into (default
     *                       {@code home/channels/voice/outbox}).
     * @param allowedUserIds the native user ids permitted to use the assistant; an EMPTY set means "allow
     *                       any" (single-user convenience), a non-empty set RESTRICTS to those ids.
     * @param timeoutSeconds the per-subprocess timeout in seconds (default {@code 120}).
     */
    public record Spec(boolean enabled, Optional<String> whisperBin, Optional<String> whisperModel,
                       Optional<String> piperBin, Optional<String> piperVoice, Optional<String> ffmpegPath,
                       Path inboxDir, Path outboxDir, Set<String> allowedUserIds, long timeoutSeconds) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Path.of(""), Path.of(""), Set.of(), DEFAULT_TIMEOUT_SECONDS);
        }

        /**
         * Whether STT+TTS are both configured (all four binaries/models present). The channel is enabled
         * AND ready only when this holds; otherwise it warns + no-ops (the no-config smoke contract).
         */
        public boolean isReady() {
            return whisperBin.isPresent() && whisperModel.isPresent()
                    && piperBin.isPresent() && piperVoice.isPresent();
        }

        /**
         * Whether a sender carrying {@code senderId} may use the assistant: any sender when
         * {@code allowedUserIds} is empty, otherwise only a listed id.
         */
        public boolean isSenderAllowed(String senderId) {
            return allowedUserIds.isEmpty() || (senderId != null && allowedUserIds.contains(senderId));
        }
    }
}
