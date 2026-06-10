package ai.forvum.channel.signal;

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
 * Reads the Signal channel's file-based configuration from {@code $FORVUM_HOME/channels/signal.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables Signal, points the channel
 * at their self-run signal-cli HTTP daemon ({@code baseUrl}), names the Signal {@code account} (the
 * daemon-registered number the channel sends as), and restricts {@code allowedUserIds} by editing one
 * file, no recompile. Mirrors {@code TelegramChannelConfig}/{@code DiscordChannelConfig}.
 *
 * <p>The engine's {@code ChannelReader} (which reads this same tree) lives in {@code forvum-engine},
 * which a Layer-3 channel must not depend on (the module enforcer). So this reader resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from the
 * {@code FORVUM_HOME} env var), falling back to {@code <user.home>/.forvum} — and reads the JSON directly
 * with Jackson (brought transitively by {@code quarkus-rest-client-jackson}). With no {@code ~/.forvum/}
 * the file is absent and {@link #read()} returns {@link Spec#empty()} (no daemon coordinates, empty
 * allow-list), so the channel boots gracefully in the CI native no-config smoke.
 *
 * <p>The config is read on demand (each {@link #read()} re-reads the file) so an operator's
 * {@code allowedUserIds} edit takes effect on the next inbound event without a restart — consistent with
 * the WatchService hot-reload model, without this module needing the engine's watcher.
 */
@ApplicationScoped
public class SignalChannelConfig {

    static final String CHANNEL_ID = "signal";
    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public SignalChannelConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /** Package-private constructor binding an explicit {@code channels/signal.json} path — for tests. */
    SignalChannelConfig(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
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

    /**
     * The current spec read from {@code channels/signal.json}. Returns {@link Spec#empty()} if the file
     * is absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real
     * misconfiguration the operator must see, not silently swallowed).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read Signal channel config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code channels/signal.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
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
        return new Spec(enabled, nonBlank(root, "baseUrl"), nonBlank(root, "account"), Set.copyOf(allowed));
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText());
    }

    /**
     * The Signal channel's resolved configuration.
     *
     * @param enabled        whether the channel is enabled (a channel is enabled unless {@code "enabled":
     *                       false}); an absent file is treated as disabled by {@link Spec#empty()}.
     * @param baseUrl        the operator-run signal-cli HTTP daemon's base URL (e.g.
     *                       {@code http://localhost:8080}), absent when unset (channel must warn + no-op,
     *                       never crash).
     * @param account        the Signal account (E.164 number) the daemon is registered for — the
     *                       {@code account} parameter on every JSON-RPC {@code send} and on the events
     *                       stream subscription. Absent when unset (warn + no-op, like {@code baseUrl}).
     * @param allowedUserIds the Signal sender ids (phone numbers and/or source UUIDs as signal-cli emits
     *                       them) permitted to use the assistant; an EMPTY set means "allow any sender"
     *                       (single-user convenience), a non-empty set RESTRICTS to exactly those ids.
     */
    public record Spec(boolean enabled, Optional<String> baseUrl, Optional<String> account,
                       Set<String> allowedUserIds) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), Optional.empty(), Set.of());
        }

        /**
         * Whether a sender carrying any of {@code senderIds} (its phone number, its source UUID — nulls
         * skipped) may use the assistant: any sender when {@code allowedUserIds} is empty, otherwise only
         * a sender with at least one id in the set. Ids are compared exactly as signal-cli emits them
         * (E.164 numbers like {@code +15550001111}; lowercase UUIDs). The friendly refusal is the
         * caller's concern.
         */
        public boolean isSenderAllowed(String... senderIds) {
            if (allowedUserIds.isEmpty()) {
                return true;
            }
            for (String id : senderIds) {
                if (id != null && allowedUserIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
    }
}
