package ai.forvum.app;

import ai.forvum.engine.config.ChannelReader;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Decides whether the binary runs as a long-lived server or in one-shot command mode (the interim
 * launch dispatch — picocli and proper run-modes are M20). A <em>server channel</em> is one whose
 * inbound surface keeps the process alive to serve; v0.1's only server channel is the Web channel
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

    /** Channel ids whose enablement keeps the process alive to serve. */
    static final Set<String> SERVER_CHANNELS = Set.of("web", TELEGRAM_ID);

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
     * Whether channel {@code id} actually serves: enabled, and — for Telegram — carrying a non-blank
     * {@code botToken}, since {@code TelegramChannel.onStart} starts its poll loop only with a token (an
     * enabled but token-less {@code telegram.json} otherwise hangs the binary serving nothing).
     */
    static boolean serves(String id, JsonNode spec) {
        if (!isEnabled(spec)) {
            return false;
        }
        return !TELEGRAM_ID.equals(id) || hasBotToken(spec);
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

    /** True if {@code spec} carries a present, non-blank {@code botToken}. */
    static boolean hasBotToken(JsonNode spec) {
        JsonNode token = spec.get("botToken");
        return token != null && !token.asText().isBlank();
    }
}
