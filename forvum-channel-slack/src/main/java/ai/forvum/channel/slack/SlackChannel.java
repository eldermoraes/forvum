package ai.forvum.channel.slack;

import ai.forvum.channel.slack.SlackChannelConfig.Spec;
import ai.forvum.channel.slack.dto.ConnectionsOpenResponse;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Slack channel's lifecycle (P2-CH): on startup it reads {@code channels/slack.json} and, when
 * enabled with BOTH tokens ({@code appToken} opens the socket, {@code botToken} sends replies), opens a
 * Socket Mode WebSocket connection (driven by {@link SlackSocketEndpoint}) on a virtual thread; on
 * shutdown it closes the connection. Mirrors the Discord channel's {@code onStart}/{@code onStop}
 * contract.
 *
 * <p><strong>Connect = mint a fresh URL, then dial it.</strong> Every connect (initial and every
 * reconnect) first calls {@code apps.connections.open} with the {@code Bearer xapp-} token to mint a
 * TEMPORARY {@code wss://} URL, then connects the endpoint to it — a Socket Mode URL is single-use and is
 * NEVER reused across reconnects (Slack refreshes them routinely via {@code type: disconnect} frames).
 *
 * <p><strong>Self-healing reconnect.</strong> A Socket Mode connection is not permanent — Slack routinely
 * sends {@code disconnect} frames ({@code refresh_requested}) and a transient network blip closes the
 * socket. When the connection closes and the channel is still {@code running} (no {@link ShutdownEvent}
 * has fired), {@link #onConnectionClosed(int)} re-opens via a fresh {@code apps.connections.open} on a
 * virtual thread with exponential backoff ({@link Backoff}: 1s, 2s, 4s … capped at 60s). The backoff
 * {@linkplain Backoff#reset() resets} on a successful {@code hello} ({@link #onConnected()}). Slack has
 * no documented fatal WebSocket close-code set (auth failures surface at {@code apps.connections.open}),
 * so every non-shutdown close reconnects; a {@linkplain #FATAL_CONNECT_ERRORS fatal}
 * {@code apps.connections.open} error (a misconfiguration, e.g. {@code invalid_auth}) STOPS the loop
 * with a WARN — reconnecting would loop forever. A deliberate {@link ShutdownEvent}
 * ({@code running == false}) never reconnects.
 *
 * <p><strong>Absent credentials → warn + no-op.</strong> If {@code channels/slack.json} is absent, the
 * channel is disabled, or either token is missing, NO connection is attempted and the bean logs a warning
 * and returns — it never throws and never blocks. This keeps the CI native no-config boot (no
 * {@code ~/.forvum/}) graceful, the same contract the M4 watcher and the other channels honor.
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> The connector is obtained per-connect from
 * {@code Instance.get()} (connectors are not reusable, per the websockets-next docs) and every connect /
 * reconnect runs on a virtual thread (the REST call + WebSocket handshake are blocking IO). The live
 * connection is held in an {@link AtomicReference} and the lifecycle flag in an {@link AtomicBoolean} —
 * no {@code synchronized} and no blocking IO under a lock. The reconnect sleep is an injectable
 * {@link Sleeper} seam so the loop is unit-testable without real wall-clock waits.
 */
@ApplicationScoped
public class SlackChannel {

    private static final Logger LOG = Logger.getLogger(SlackChannel.class);

    /**
     * {@code apps.connections.open} error strings that are NON-recoverable credential/configuration
     * failures: reconnecting on these would loop forever on a misconfiguration, so the channel stops
     * with a WARN. Anything else (e.g. {@code ratelimited}, a transient 5xx surfaced as an exception)
     * retries with backoff.
     */
    static final Set<String> FATAL_CONNECT_ERRORS = Set.of(
            "invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired",
            "not_allowed_token_type", "forbidden_team");

    @Inject
    SlackChannelConfig config;

    /**
     * Connector factory for the {@link SlackSocketEndpoint} client endpoint. A fresh connector per
     * connect ({@code Instance.get()}) is required — connectors are single-use and not thread-safe.
     */
    @Inject
    Instance<WebSocketConnector<SlackSocketEndpoint>> connectors;

    /** The Slack Web API client used to mint the Socket Mode URL ({@code apps.connections.open}). */
    @Inject
    @RestClient
    SlackRestClient rest;

    /** Package-private so the no-config boot test can assert it stays null (no connection attempted). */
    ExecutorService connectExecutor;
    private final AtomicReference<WebSocketClientConnection> connection = new AtomicReference<>();

    /** True between {@code onStart} (enabled + both tokens present) and {@code onStop}; gates reconnects. */
    final AtomicBoolean running = new AtomicBoolean(false);
    /** The app-level {@code xapp-} token minting socket URLs; captured at {@code onStart}. */
    private volatile String appToken;
    /** The bot {@code xoxb-} token for the reply path; rides each connection's user data. */
    private volatile String botToken;
    /** The reconnect backoff schedule, reset on a successful hello. */
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
            LOG.info("Slack channel disabled (no channels/slack.json, or \"enabled\": false); "
                    + "not connecting to Socket Mode.");
            return;
        }
        if (spec.botToken().isEmpty() || spec.appToken().isEmpty()) {
            LOG.warnf("Slack channel enabled but missing %s in channels/slack.json; not connecting to "
                    + "Socket Mode. Set both \"botToken\" (xoxb-) and \"appToken\" (xapp-) to activate "
                    + "the channel.",
                    spec.botToken().isEmpty() && spec.appToken().isEmpty()
                            ? "botToken and appToken"
                            : spec.botToken().isEmpty() ? "botToken" : "appToken");
            return;
        }
        appToken = spec.appToken().get();
        botToken = spec.botToken().get();
        running.set(true);
        connectExecutor = Executors.newVirtualThreadPerTaskExecutor();
        connectExecutor.submit(this::connect);
        LOG.info("Slack channel starting: opening a Socket Mode connection on a virtual thread.");
    }

    /**
     * Mint a fresh Socket Mode URL via {@code apps.connections.open}, then open the WebSocket
     * connection carrying the bot token on the connection's user data. A transient failure (and while
     * still {@code running}) schedules a backoff reconnect, so an outage at startup self-heals rather
     * than leaving the channel permanently inactive; a {@linkplain #FATAL_CONNECT_ERRORS fatal} API
     * error stops the channel.
     */
    void connect() {
        if (!running.get()) {
            return;
        }
        String socketUrl;
        try {
            ConnectionsOpenResponse response = rest.connectionsOpen("Bearer " + appToken);
            if (response == null || !response.ok() || response.url() == null
                    || response.url().isBlank()) {
                handleConnectionsOpenFailure(response == null ? "empty response" : response.error());
                return;
            }
            socketUrl = response.url();
        } catch (RuntimeException e) {
            LOG.warnf("Slack: apps.connections.open failed (%s); scheduling a reconnect.",
                    redact(e.getMessage()));
            scheduleReconnect();
            return;
        }
        try {
            WebSocketClientConnection conn = connectors.get()
                    .baseUri(URI.create(socketUrl))
                    .userData(TypedKey.forString(SlackSocketEndpoint.BOT_TOKEN_KEY), botToken)
                    .connectAndAwait();
            connection.set(conn);
            LOG.info("Slack Socket Mode: connected.");
        } catch (RuntimeException e) {
            LOG.warnf("Slack: failed to connect to the Socket Mode URL (%s); scheduling a reconnect.",
                    redact(e.getMessage()));
            scheduleReconnect();
        }
    }

    /**
     * {@code apps.connections.open} answered {@code ok: false}. A {@linkplain #FATAL_CONNECT_ERRORS
     * fatal} credential error stops the channel (do not loop forever on a misconfiguration); anything
     * else (rate limiting, a transient API hiccup) schedules a backoff reconnect. The error string is a
     * Slack-defined token ({@code invalid_auth}, ...) — it never contains a secret.
     */
    void handleConnectionsOpenFailure(String error) {
        if (error != null && FATAL_CONNECT_ERRORS.contains(error)) {
            running.set(false);
            LOG.warnf("Slack: apps.connections.open failed with a fatal error \"%s\" (a "
                    + "misconfiguration, e.g. a bad/revoked appToken); NOT reconnecting. Fix "
                    + "channels/slack.json and restart.", error);
            return;
        }
        LOG.warnf("Slack: apps.connections.open returned ok=false (%s); scheduling a reconnect.", error);
        scheduleReconnect();
    }

    /**
     * The Socket Mode connection closed. Called by {@link SlackSocketEndpoint}'s {@code @OnClose} with
     * the close code. Slack has no fatal close-code set (credential failures surface at
     * {@code apps.connections.open}), so the policy is two-armed: a deliberate shutdown
     * ({@code running == false}) does nothing; any other close (a {@code disconnect} frame we honored,
     * a network drop) schedules a backoff reconnect through a FRESH {@code apps.connections.open}.
     */
    void onConnectionClosed(int closeCode) {
        connection.set(null);
        if (!running.get()) {
            return; // a deliberate shutdown closed the socket — never reconnect.
        }
        scheduleReconnect();
    }

    /** A successful hello established a live socket — reset the backoff so the schedule starts over. */
    void onConnected() {
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
        LOG.infof("Slack Socket Mode: reconnecting in %d ms (fresh apps.connections.open).", delay);
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
                LOG.warnf("Slack: error closing the Socket Mode connection on shutdown (%s).",
                        redact(e.getMessage()));
            }
        }
        if (connectExecutor != null) {
            connectExecutor.shutdownNow();
        }
    }

    /** Whether the channel started its connect worker — a METHOD so a CDI client proxy delegates. */
    boolean started() {
        return running.get();
    }

    /**
     * Redact Slack tokens from a log-bound string. Both token families share the {@code x...-} prefix
     * shape ({@code xoxb-} bot, {@code xapp-} app-level, {@code xoxp-} user, ...); they travel in
     * {@code Authorization: Bearer <token>} headers and the connect bootstrap, so an exception message
     * echoing a header or request must be masked before logging. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("\\bx(?:ox[a-z]|app)-[A-Za-z0-9-]+", "<redacted>");
    }
}
