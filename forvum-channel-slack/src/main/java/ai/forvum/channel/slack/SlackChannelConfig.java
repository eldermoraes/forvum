package ai.forvum.channel.slack;

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
 * Reads the Slack channel's file-based configuration from {@code $FORVUM_HOME/channels/slack.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables Slack, sets the two tokens,
 * and restricts {@code allowedUserIds} by editing one file, no recompile. Mirrors
 * {@code TelegramChannelConfig}/{@code DiscordChannelConfig}.
 *
 * <p>Slack Socket Mode needs TWO tokens: the app-level {@code appToken} ({@code xapp-...}) authorizes
 * {@code apps.connections.open} (minting the socket URL), and the bot {@code botToken} ({@code xoxb-...})
 * authorizes {@code chat.postMessage} (the reply path). Both must be present for the channel to serve.
 *
 * <p>The engine's {@code ChannelReader} (which reads this same tree) lives in {@code forvum-engine},
 * which a Layer-3 channel must not depend on (the module enforcer). So this reader resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from the
 * {@code FORVUM_HOME} env var), falling back to {@code <user.home>/.forvum} — and reads the JSON directly
 * with Jackson (brought transitively by {@code quarkus-rest-client-jackson}). With no {@code ~/.forvum/}
 * the file is absent and {@link #read()} returns {@link Spec#empty()} (no tokens, empty allow-list), so
 * the channel boots gracefully in the CI native no-config smoke.
 *
 * <p>The config is read on demand (each {@link #read()} re-reads the file) so an operator's edit takes
 * effect on the next inbound event without a restart — consistent with the WatchService hot-reload
 * model, without this module needing the engine's watcher.
 */
@ApplicationScoped
public class SlackChannelConfig {

    static final String CHANNEL_ID = "slack";
    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public SlackChannelConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /** Package-private constructor binding an explicit {@code channels/slack.json} path — for tests. */
    SlackChannelConfig(Path configFile) {
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
     * The current spec read from {@code channels/slack.json}. Returns {@link Spec#empty()} if the file
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
                    "Cannot read Slack channel config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code channels/slack.json} JSON tree into a {@link Spec}. Package-private for tests. */
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
                if (!id.asText().isBlank()) {
                    allowed.add(id.asText().trim());
                }
            }
        }
        return new Spec(enabled, token(root, "botToken"), token(root, "appToken"), Set.copyOf(allowed));
    }

    /** A token field's value, absent when missing or blank (a blank credential is no credential). */
    private static Optional<String> token(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText());
    }

    /**
     * The Slack channel's resolved configuration.
     *
     * @param enabled        whether the channel is enabled (a channel is enabled unless {@code "enabled":
     *                       false}); an absent file is treated as disabled by {@link Spec#empty()}.
     * @param botToken       the bot ({@code xoxb-}) token authorizing {@code chat.postMessage}, absent
     *                       when unset (channel must warn + no-op, never crash).
     * @param appToken       the app-level ({@code xapp-}) token authorizing
     *                       {@code apps.connections.open}, absent when unset (same no-op contract).
     * @param allowedUserIds the Slack user ids (e.g. {@code U0123ABC}) permitted to use the bot; an
     *                       EMPTY set means "allow any user" (single-user convenience), a non-empty set
     *                       RESTRICTS to exactly those ids.
     */
    public record Spec(boolean enabled, Optional<String> botToken, Optional<String> appToken,
                       Set<String> allowedUserIds) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), Optional.empty(), Set.of());
        }

        /**
         * Whether {@code userId} may use the bot: any user when {@code allowedUserIds} is empty,
         * otherwise only ids in the set. The friendly refusal is the caller's concern.
         */
        public boolean isUserAllowed(String userId) {
            return allowedUserIds.isEmpty() || allowedUserIds.contains(userId);
        }
    }
}
