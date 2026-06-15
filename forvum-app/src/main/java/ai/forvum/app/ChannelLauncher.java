package ai.forvum.app;

import ai.forvum.engine.config.ChannelReader;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/**
 * Decides whether the binary runs as a long-lived server or exits in command mode. Invoked from
 * {@link RootCommand#call()} (the picocli default command) as the permanent launch dispatch. A
 * <em>server channel</em> is one whose inbound surface keeps the process alive to serve; v0.1's only
 * server channel is the Web channel
 * (its vertx-http/WebSocket server runs in background threads, so {@code run()} need only block). M17
 * adds Telegram's long-poll loop to this set — but only when it actually serves: Telegram counts as a
 * live server channel only when its config carries a non-blank {@code botToken}, mirroring
 * {@code TelegramChannel.onStart} (which warns and no-ops without one). An enabled but token-less
 * {@code telegram.json} would otherwise hang the binary in server mode serving nothing.
 *
 * <p>Enablement is read from {@code $FORVUM_HOME/channels/<id>.json} via the engine's
 * {@link ChannelReader}: a channel is enabled unless its config explicitly sets {@code "enabled": false}
 * (so simply dropping a {@code web.json} turns it on). With no {@code ~/.forvum/} the reader returns an
 * empty list, so the binary stays in command mode — the contract the CI native smoke runs with.
 */
@ApplicationScoped
public class ChannelLauncher {

    /** Channel id of the Telegram channel, which additionally requires a {@code botToken} to serve. */
    static final String TELEGRAM_ID = "telegram";

    /** Channel id of the Discord channel, which (like Telegram) additionally requires a {@code botToken}. */
    static final String DISCORD_ID = "discord";

    /** Channel id of the Signal channel, which requires the daemon {@code baseUrl} AND {@code account}. */
    static final String SIGNAL_ID = "signal";

    /**
     * Channel id of the Matrix channel, which requires a {@code homeserver}, an {@code accessToken},
     * AND a {@code userId} (Matrix {@code /sync} echoes the bot's own sends, so
     * {@code MatrixChannel.onStart} refuses to start the loop without the bot's own id — the
     * self-echo gate).
     */
    static final String MATRIX_ID = "matrix";

    /**
     * Channel id of the Slack channel, which requires BOTH a {@code botToken} (xoxb-, the reply path)
     * and an {@code appToken} (xapp-, opens the Socket Mode connection) to serve — the first multi-key
     * entry exercising the {@code allMatch} gate.
     */
    static final String SLACK_ID = "slack";

    /**
     * Per-channel config keys that must ALL be present and non-blank for the channel to count as
     * serving. Generalizes the original single {@code botToken} gate: each credential-gated channel
     * declares its own key set (Slack needs {@code botToken}+{@code appToken}, Matrix
     * {@code homeserver}+{@code accessToken}+{@code userId}, Signal {@code baseUrl}+{@code account} —
     * each entry lands WITH its channel module, never before, or an enabled config for an absent module
     * would hang the binary in server mode serving nothing, the M17 trap). A channel with no entry has
     * no key requirement. The keys mirror each channel's own {@code onStart} gate (whatever makes it
     * warn + no-op makes it non-serving here), so they are not strictly credentials — Matrix's
     * {@code userId} is the bot's own identity, required for its self-echo filter.
     */
    static final Map<String, Set<String>> REQUIRED_SERVE_KEYS = Map.of(
            TELEGRAM_ID, Set.of("botToken"),
            DISCORD_ID, Set.of("botToken"),
            SLACK_ID, Set.of("botToken", "appToken"),
            MATRIX_ID, Set.of("homeserver", "accessToken", "userId"),
            SIGNAL_ID, Set.of("baseUrl", "account"));

    /** Channel ids whose enablement keeps the process alive to serve. */
    static final Set<String> SERVER_CHANNELS =
            Set.of("web", TELEGRAM_ID, DISCORD_ID, SLACK_ID, MATRIX_ID, SIGNAL_ID);

    /**
     * Channel ids whose enablement runs an interactive foreground loop instead of a background server.
     * v0.1's only one is the TUI (M15): its stdin REPL blocks the foreground, so the binary runs it
     * directly rather than {@code Quarkus.waitForExit()}.
     */
    static final Set<String> FOREGROUND_CHANNELS = Set.of("tui");

    @Inject
    ChannelReader channels;

    /** True if any configured server channel is live — the binary must stay alive to serve it. */
    public boolean shouldRunAsServer() {
        return channels.ids().stream()
                .filter(SERVER_CHANNELS::contains)
                .anyMatch(id -> serves(id, channels.read(id).orElse(null)));
    }

    /**
     * Whether channel {@code id} actually serves: enabled, and carrying every
     * {@link #REQUIRED_SERVE_KEYS required key} non-blank, since a credential-gated channel's
     * {@code onStart} starts the inbound surface (poll loop / gateway connection) only with its
     * credentials (an enabled but credential-less config would otherwise hang the binary in server
     * mode serving nothing).
     */
    static boolean serves(String id, JsonNode spec) {
        if (!isEnabled(spec)) {
            return false;
        }
        return REQUIRED_SERVE_KEYS.getOrDefault(id, Set.of()).stream()
                .allMatch(key -> hasNonBlank(spec, key));
    }

    /** True if an interactive foreground channel (the TUI) is enabled — run it in the foreground. */
    public boolean shouldRunInteractive() {
        return channels.ids().stream()
                .filter(FOREGROUND_CHANNELS::contains)
                .anyMatch(id -> isEnabled(channels.read(id).orElse(null)));
    }

    /** A channel is enabled unless its config explicitly sets {@code "enabled": false}. */
    static boolean isEnabled(JsonNode spec) {
        if (spec == null) {
            return false;
        }
        JsonNode enabled = spec.get("enabled");
        return enabled == null || enabled.asBoolean(true);
    }

    /** True if {@code spec} carries a present, non-blank value for {@code key}. */
    static boolean hasNonBlank(JsonNode spec, String key) {
        JsonNode value = spec.get(key);
        return value != null && !value.asText().isBlank();
    }
}
