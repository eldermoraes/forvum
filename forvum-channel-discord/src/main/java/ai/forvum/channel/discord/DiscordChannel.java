package ai.forvum.channel.discord;

import ai.forvum.channel.discord.DiscordChannelConfig.Spec;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Discord channel's lifecycle (P2-CH): on startup it reads {@code channels/discord.json} and, when
 * enabled with a {@code botToken}, opens a persistent Gateway v10 WebSocket connection (driven by
 * {@link DiscordGatewayEndpoint}) on a virtual thread; on shutdown it closes the connection. Mirrors the
 * Telegram channel's {@code onStart}/{@code onStop} contract.
 *
 * <p><strong>Self-healing reconnect (v0.1: fresh IDENTIFY).</strong> A Discord gateway connection is not
 * permanent — Discord routinely sends RECONNECT (op 7), can INVALIDATE the session (op 9), and a transient
 * network blip closes the socket. When the connection closes and the channel is still {@code running} (no
 * {@link ShutdownEvent} has fired), {@link #onConnectionClosed(int)} re-opens the gateway on a virtual
 * thread with exponential backoff ({@link Backoff}: 1s, 2s, 4s … capped at 60s) so the channel self-heals
 * without a reconnect storm. Each reconnect performs a <em>fresh IDENTIFY</em> (a brand-new session); the
 * {@link GatewayState} is not replayed. Full RESUME (op 6 with {@code resume_gateway_url} + {@code
 * session_id} + last seq to replay missed events) is a DEFERRED follow-up — the captured resume context in
 * {@link GatewayState} is unused by the reconnect path in v0.1. The backoff {@linkplain Backoff#reset()
 * resets} on a successful READY ({@link #onReady()}). A FATAL gateway close code (the 4xxx set Discord
 * documents as non-recoverable, e.g. {@code 4004} authentication failed / {@code 4013}-{@code 4014} invalid
 * or disallowed intents) STOPS the loop with a WARN — reconnecting would loop forever on a misconfiguration.
 * A deliberate {@link ShutdownEvent} ({@code running == false}) never reconnects.
 *
 * <p><strong>Absent token → warn + no-op.</strong> If {@code channels/discord.json} is absent, the channel
 * is disabled, or no {@code botToken} is set, NO connection is attempted and the bean logs a warning and
 * returns — it never throws and never blocks. This keeps the CI native no-config boot (no
 * {@code ~/.forvum/}) graceful, the same contract the M4 watcher and the web/telegram channels honor.
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> The connector is obtained per-connect from
 * {@code Instance.get()} (connectors are not reusable, per the websockets-next docs) and every connect /
 * reconnect runs on a virtual thread (the WebSocket handshake is blocking IO). The live connection is held
 * in an {@link AtomicReference} and the lifecycle flag in an {@link AtomicBoolean} — no {@code synchronized}
 * and no blocking IO under a lock. The reconnect sleep is an injectable {@link Sleeper} seam so the loop is
 * unit-testable without real wall-clock waits.
 */
@ApplicationScoped
public class DiscordChannel {

    private static final Logger LOG = Logger.getLogger(DiscordChannel.class);

    /**
     * Discord gateway close codes that are NON-recoverable: reconnecting on these would loop forever on a
     * misconfiguration, so the channel stops with a WARN. {@code 4004} authentication failed,
     * {@code 4010} invalid shard, {@code 4011} sharding required, {@code 4012} invalid API version,
     * {@code 4013} invalid intent(s), {@code 4014} disallowed (privileged) intent(s).
     */
    static final Set<Integer> FATAL_CLOSE_CODES = Set.of(4004, 4010, 4011, 4012, 4013, 4014);

    @Inject
    DiscordChannelConfig config;

    /**
     * Connector factory for the {@link DiscordGatewayEndpoint} client endpoint. A fresh connector per
     * connect ({@code Instance.get()}) is required — connectors are single-use and not thread-safe.
     */
    @Inject
    Instance<WebSocketConnector<DiscordGatewayEndpoint>> connectors;

    /**
     * The initial gateway URL. {@code wss://gateway.discord.gg/?v=10&encoding=json} (plain JSON, NO
     * zlib-stream in v0.1). Configurable so a test or a future resume points elsewhere.
     */
    @ConfigProperty(name = "forvum.channel.discord.gateway-url",
            defaultValue = "wss://gateway.discord.gg/?v=10&encoding=json")
    String gatewayUrl;

    /** Package-private so the no-config boot test can assert it stays null (no connection attempted). */
    ExecutorService connectExecutor;
    private final AtomicReference<WebSocketClientConnection> connection = new AtomicReference<>();

    /** True between {@code onStart} (enabled + token present) and {@code onStop}; gates every reconnect. */
    final AtomicBoolean running = new AtomicBoolean(false);
    /** The bot token to (re-)IDENTIFY with; captured at {@code onStart}, read by each reconnect. */
    private volatile String botToken;
    /** The reconnect backoff schedule, reset on a successful READY. */
    final Backoff backoff = new Backoff();
    /** Sleep seam (default {@link Thread#sleep}); a test substitutes a no-op/recording sleeper. */
    Sleeper sleeper = Thread::sleep;

    /** An interruptible sleep, abstracted so a reconnect test asserts the backoff without real waits. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    void onStart(@Observes StartupEvent event) {
        Spec spec = config.read();
        if (!spec.enabled()) {
            LOG.info("Discord channel disabled (no channels/discord.json, or \"enabled\": false); "
                    + "not connecting to the gateway.");
            return;
        }
        if (spec.botToken().isEmpty()) {
            LOG.warn("Discord channel enabled but no botToken in channels/discord.json; not connecting to "
                    + "the gateway. Set \"botToken\" to activate the channel.");
            return;
        }
        botToken = spec.botToken().get();
        running.set(true);
        connectExecutor = Executors.newVirtualThreadPerTaskExecutor();
        connectExecutor.submit(this::connect);
        LOG.info("Discord channel starting: connecting to the gateway on a virtual thread.");
    }

    /**
     * Open the gateway connection, carrying the bot token on the connection's user data. On a failed
     * connect (and while still {@code running}) the channel schedules a backoff reconnect, so a gateway
     * outage at startup self-heals rather than leaving the channel permanently inactive.
     */
    void connect() {
        if (!running.get()) {
            return;
        }
        try {
            WebSocketClientConnection conn = connectors.get()
                    .baseUri(URI.create(gatewayUrl))
                    .userData(TypedKey.forString(DiscordGatewayEndpoint.TOKEN_KEY), botToken)
                    .connectAndAwait();
            connection.set(conn);
            LOG.info("Discord gateway: connected.");
        } catch (RuntimeException e) {
            LOG.warnf("Discord: failed to connect to the gateway (%s); scheduling a reconnect.",
                    redact(e.getMessage()));
            scheduleReconnect();
        }
    }

    /**
     * The gateway connection closed. Called by {@link DiscordGatewayEndpoint}'s {@code @OnClose} with the
     * close code. Reconnect policy (v0.1, fresh IDENTIFY):
     * <ul>
     *   <li>{@code running == false} (deliberate {@link ShutdownEvent}) → do nothing.</li>
     *   <li>a {@linkplain #FATAL_CLOSE_CODES fatal} 4xxx close code → WARN and STOP (do not loop forever
     *       on a misconfiguration).</li>
     *   <li>otherwise (op 7 reconnect, op 9 invalid session, a transient network close) → schedule a
     *       backoff reconnect with a fresh IDENTIFY.</li>
     * </ul>
     */
    void onConnectionClosed(int closeCode) {
        connection.set(null);
        if (!running.get()) {
            return; // a deliberate shutdown closed the socket — never reconnect.
        }
        if (FATAL_CLOSE_CODES.contains(closeCode)) {
            running.set(false);
            LOG.warnf("Discord gateway closed with a fatal code %d (a misconfiguration, e.g. bad token or "
                    + "disallowed intents); NOT reconnecting. Fix channels/discord.json and restart.",
                    closeCode);
            return;
        }
        scheduleReconnect();
    }

    /** A successful READY established a fresh session — reset the backoff so the schedule starts over. */
    void onReady() {
        backoff.reset();
    }

    /**
     * Schedule a single reconnect on a virtual thread after the next backoff delay. Re-checks
     * {@code running} both before sleeping and after waking, so a shutdown during the backoff window
     * cancels the reconnect. A backoff sleep interrupted by {@code shutdownNow()} ends the attempt quietly.
     */
    void scheduleReconnect() {
        if (!running.get() || connectExecutor == null || connectExecutor.isShutdown()) {
            return;
        }
        long delay = backoff.nextDelayMillis();
        LOG.infof("Discord gateway: reconnecting in %d ms (fresh IDENTIFY).", delay);
        connectExecutor.submit(() -> {
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // shutdownNow during the backoff — abandon the reconnect.
            }
            if (running.get()) {
                connect();
            }
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        running.set(false);
        WebSocketClientConnection conn = connection.getAndSet(null);
        if (conn != null) {
            try {
                conn.closeAndAwait();
            } catch (RuntimeException e) {
                LOG.warnf("Discord: error closing the gateway connection on shutdown (%s).",
                        redact(e.getMessage()));
            }
        }
        if (connectExecutor != null) {
            connectExecutor.shutdownNow();
        }
    }

    /**
     * Redact a Discord bot token from a log-bound string. Discord tokens never appear in a URL (they
     * travel in the IDENTIFY frame and the {@code Authorization} header, neither logged), but an
     * exception message could still echo a {@code Bot <token>} header value, so any {@code Bot <token>}
     * occurrence is masked. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("(?i)Bot\\s+[A-Za-z0-9._-]+", "Bot <redacted>");
    }
}
