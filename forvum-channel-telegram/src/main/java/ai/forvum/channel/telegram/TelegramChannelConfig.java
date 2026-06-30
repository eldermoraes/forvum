package ai.forvum.channel.telegram;

import ai.forvum.sdk.ChannelAdmissionPolicy;

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
 * Reads the Telegram channel's file-based configuration from {@code $FORVUM_HOME/channels/telegram.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables Telegram, sets the bot
 * token, and restricts {@code allowedUserIds} by editing one file, no recompile.
 *
 * <p>The engine's {@code ChannelReader} (which reads this same tree) lives in {@code forvum-engine},
 * which a Layer-3 channel must not depend on (the module enforcer). So this reader resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from the
 * {@code FORVUM_HOME} env var), falling back to {@code <user.home>/.forvum} — and reads the JSON
 * directly with Jackson (brought transitively by {@code quarkus-rest-client-jackson}). With no
 * {@code ~/.forvum/} the file is absent and {@link #read()} returns {@link Spec#empty()} (no token,
 * empty allow-list), so the channel boots gracefully in the CI native no-config smoke.
 *
 * <p>The config is read on demand (each {@link #read()} re-reads the file) so an operator's edit takes
 * effect on the next poll cycle without a restart — consistent with the WatchService hot-reload model,
 * without this module needing the engine's watcher.
 */
@ApplicationScoped
public class TelegramChannelConfig {

    static final String CHANNEL_ID = "telegram";
    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public TelegramChannelConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /** Package-private constructor binding an explicit {@code channels/telegram.json} path — for tests. */
    TelegramChannelConfig(Path configFile) {
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
     * The current spec read from {@code channels/telegram.json}. Returns {@link Spec#empty()} if the file
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
                    "Cannot read Telegram channel config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code channels/telegram.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return Spec.empty();
        }
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode == null || enabledNode.asBoolean(true);

        JsonNode allowAllNode = root.get("allowAllUsers");
        boolean allowAllUsers = allowAllNode != null && allowAllNode.asBoolean(false);

        JsonNode tokenNode = root.get("botToken");
        Optional<String> token = tokenNode == null || tokenNode.asText().isBlank()
                ? Optional.empty()
                : Optional.of(tokenNode.asText());

        Set<Long> allowed = new LinkedHashSet<>();
        JsonNode allowedNode = root.get("allowedUserIds");
        if (allowedNode != null && allowedNode.isArray()) {
            for (JsonNode id : allowedNode) {
                allowed.add(id.asLong());
            }
        }
        return new Spec(enabled, token, Set.copyOf(allowed), allowAllUsers);
    }

    /**
     * The Telegram channel's resolved configuration.
     *
     * @param enabled        whether the channel is enabled (a channel is enabled unless {@code "enabled":
     *                       false}); an absent file is treated as disabled by {@link Spec#empty()}.
     * @param botToken       the bot token, absent when unset (channel must warn + no-op, never crash).
     * @param allowedUserIds the Telegram user ids permitted to use the bot; an EMPTY set now DENIES every
     *                       user unless {@code allowAllUsers} is true (#170 fail-closed), a non-empty set
     *                       RESTRICTS to exactly those ids.
     * @param allowAllUsers  the explicit public-mode opt-in: when {@code allowedUserIds} is empty, admit
     *                       ANY user (restores the pre-#170 wide-open behavior, conspicuously).
     */
    public record Spec(boolean enabled, Optional<String> botToken, Set<Long> allowedUserIds,
                       boolean allowAllUsers) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), Set.of(), false);
        }

        /**
         * Whether {@code userId} may use the bot (#170 fail-closed): a non-empty {@code allowedUserIds}
         * restricts to its members; an empty one admits a user only under {@code allowAllUsers}. The
         * friendly refusal is the caller's concern.
         */
        public boolean isUserAllowed(long userId) {
            return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId);
        }
    }
}
