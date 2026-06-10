package ai.forvum.channel.discord;

import ai.forvum.channel.discord.GatewayProtocol.Acknowledged;
import ai.forvum.channel.discord.GatewayProtocol.Ignored;
import ai.forvum.channel.discord.GatewayProtocol.MessageReceived;
import ai.forvum.channel.discord.GatewayProtocol.Reaction;
import ai.forvum.channel.discord.GatewayProtocol.Reconnect;
import ai.forvum.channel.discord.GatewayProtocol.ReIdentify;
import ai.forvum.channel.discord.GatewayProtocol.SendIdentify;
import ai.forvum.channel.discord.GatewayProtocol.SendResume;
import ai.forvum.channel.discord.dto.GatewayPayload;
import ai.forvum.channel.discord.dto.Ready;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The Discord Gateway v10 client endpoint over {@code quarkus-websockets-next} CLIENT mode (the
 * native-clean transport the P2-CH readiness spike compiled + booted). Connected by {@link DiscordChannel}
 * to {@code wss://gateway.discord.gg/?v=10&encoding=json}, it implements the opcode flow:
 *
 * <ol>
 *   <li><strong>HELLO (op 10)</strong> → send IDENTIFY (op 2; token + Forvum intents) for a fresh
 *       session, or RESUME (op 6; token + captured {@code session_id} + last seq) when the
 *       {@link GatewayState} holds a resumable session — the gateway then replays missed events and
 *       dispatches {@code RESUMED}. Either way the heartbeat loop is armed with the server-supplied
 *       {@code heartbeat_interval}.</li>
 *   <li><strong>HEARTBEAT loop (op 1)</strong> on a dedicated <em>virtual thread</em> (never the event
 *       loop): every {@code heartbeat_interval} ms it sends {@code { "op": 1, "d": <last seq> }},
 *       reading the last sequence from {@link GatewayState} (an atomic).</li>
 *   <li><strong>DISPATCH (op 0)</strong>: {@code READY} captures session_id + resume_gateway_url;
 *       {@code MESSAGE_CREATE} drives a turn via {@link MessageProcessor}.</li>
 *   <li><strong>RECONNECT (op 7)</strong> / <strong>INVALID_SESSION (op 9)</strong> close the connection
 *       — with the {@link #RECONNECT_CLOSE_CODE 4000 resume-intent code} when the session should survive
 *       (op 7, op 9 {@code d=true}; a client 1000/1001 close would invalidate it), or the default 1000
 *       when it should not (op 9 {@code d=false}, which resets the state first). {@code @OnClose} then
 *       hands the close code to {@link DiscordChannel#onConnectionClosed(int)}, which re-opens the
 *       gateway with exponential backoff — dialing the resume URL for an op-6 RESUME when the session is
 *       still resumable, else the base URL for a fresh IDENTIFY — unless the channel is shutting down or
 *       the close code is a fatal 4xxx.</li>
 * </ol>
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> Inbound frames run {@code @RunOnVirtualThread} so
 * handling blocks on a virtual thread, never the event loop. Shared seq/session state lives in
 * {@link GatewayState} atomics — no {@code synchronized}. The heartbeat thread's lifecycle (start on
 * HELLO, stop on close) is guarded by a {@link ReentrantLock}, and the only blocking IO under that lock is
 * none: the lock guards just the thread reference swap; the heartbeat's own {@code sendTextAndAwait} and
 * sleep happen outside any lock (no carrier pinning).
 *
 * <p>{@code @WebSocketClient} endpoints are CDI beans; this one is the default {@code @Singleton} (one
 * gateway connection per process), so its collaborators are injected and its only mutable field
 * (the heartbeat thread) is lock-guarded.
 */
@WebSocketClient(path = "/")
public class DiscordGatewayEndpoint {

    private static final Logger LOG = Logger.getLogger(DiscordGatewayEndpoint.class);

    /** Per-connection user-data key carrying the bot token (set by the connector before connect). */
    static final String TOKEN_KEY = "forvum.discord.botToken";

    /**
     * The application close code (4000) for every close made with INTENT TO RESUME. Discord's Gateway
     * docs (Disconnecting/Resuming): a CLIENT close with 1000 or 1001 invalidates the session, so
     * closing NORMAL on op 7 / a resumable op 9 / a failed heartbeat would defeat the op-6 RESUME the
     * reconnect is about to attempt — the resume would be refused (op 9 {@code d=false}) and a second
     * round-trip would re-IDENTIFY. A deliberate shutdown and a non-resumable op 9 keep the default
     * 1000 close (those sessions SHOULD die).
     */
    static final int RECONNECT_CLOSE_CODE = 4000;

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    GatewayState state;

    @Inject
    MessageProcessor processor;

    @Inject
    DiscordChannelConfig config;

    @Inject
    DiscordChannel channel;

    @Inject
    @RestClient
    DiscordRestClient rest;

    /** Guards the heartbeat-thread reference swap only (no blocking IO is performed under it). */
    private final ReentrantLock heartbeatLock = new ReentrantLock();
    private Thread heartbeatThread;

    @OnOpen
    void onOpen(WebSocketClientConnection connection) {
        LOG.info("Discord gateway connection opened; awaiting HELLO.");
    }

    /**
     * Handle one inbound gateway text frame on a virtual thread: parse → {@link GatewayProtocol#decide}
     * (updates the atomic seq/session) → act on the {@link Reaction}. A parse/handle failure for one frame
     * is logged (redacted) and swallowed so a single bad frame never kills the gateway.
     */
    @OnTextMessage
    @RunOnVirtualThread
    void onText(WebSocketClientConnection connection, String frame) {
        GatewayPayload payload;
        try {
            payload = GatewayProtocol.parse(mapper, frame);
        } catch (RuntimeException e) {
            LOG.warnf("Discord: dropping an unparseable gateway frame (%s).",
                    DiscordChannel.redact(e.getMessage()));
            return;
        }
        Reaction reaction = GatewayProtocol.decide(mapper, payload, state);
        // READY needs the typed payload to capture the resume context; decide() returns Acknowledged for
        // it, so the endpoint captures it here (the only state decide() cannot set without the typed Ready).
        if (payload.op() == GatewayProtocol.OP_DISPATCH
                && GatewayProtocol.EVENT_READY.equals(payload.t())) {
            Ready ready = GatewayProtocol.readyOf(mapper, payload);
            state.onReady(ready.sessionId(), ready.resumeGatewayUrl());
            channel.onReady(); // a healthy session restarts the reconnect-backoff schedule
            LOG.info("Discord gateway READY; session established.");
        }
        // RESUMED closes an op-6 resume: the gateway finished replaying missed events on the continued
        // session, so the reconnect-backoff schedule restarts exactly as on READY.
        if (payload.op() == GatewayProtocol.OP_DISPATCH
                && GatewayProtocol.EVENT_RESUMED.equals(payload.t())) {
            channel.onReady();
            LOG.info("Discord gateway RESUMED; session continued, missed events replayed.");
        }
        act(connection, reaction);
    }

    /** Apply a decided {@link Reaction} to the live connection (the only IO-performing step). */
    private void act(WebSocketClientConnection connection, Reaction reaction) {
        switch (reaction) {
            case SendIdentify identify -> {
                sendIdentify(connection);
                startHeartbeat(connection, identify.heartbeatIntervalMillis());
            }
            case SendResume resume -> {
                sendResume(connection);
                startHeartbeat(connection, resume.heartbeatIntervalMillis());
            }
            case MessageReceived received ->
                    processor.process(received.message(), config.read(), rest, authorization(connection));
            case Reconnect ignored ->
                    closeForReconnect(connection, "gateway requested reconnect (op 7)");
            case ReIdentify reIdentify -> {
                // d=true keeps the session alive for an op-6 RESUME → close with resume intent;
                // d=false means the session is dead (decide() already reset the state) → the
                // deliberate session-invalidating 1000 close is correct.
                if (reIdentify.resumable()) {
                    closeForReconnect(connection, "gateway invalidated the session (op 9, resumable=true)");
                } else {
                    closeQuietly(connection, "gateway invalidated the session (op 9, resumable=false)");
                }
            }
            case Acknowledged ignored -> { /* state already updated; nothing to send */ }
            case Ignored ignored -> { /* an unhandled frame; nothing to do */ }
        }
    }

    /** Send the IDENTIFY (op 2) for the connection's bot token. */
    private void sendIdentify(WebSocketClientConnection connection) {
        connection.sendTextAndAwait(GatewayProtocol.encodeIdentify(mapper, tokenOf(connection)));
        LOG.info("Discord gateway: sent IDENTIFY.");
    }

    /**
     * Send the RESUME (op 6) continuing the captured session. {@code decide()} returns
     * {@code SendResume} only when {@link GatewayState#canResume()}, but the state lives in atomics —
     * if it was reset in between (an op 9 {@code d=false} racing this frame), fall back to a fresh
     * IDENTIFY rather than sending a malformed RESUME. Package-private so the fallback branch is
     * unit-testable (it is unreachable through SERIAL single-threaded {@code onText} calls).
     */
    void sendResume(WebSocketClientConnection connection) {
        var sessionId = state.sessionId();
        var lastSequence = state.lastSequence();
        if (sessionId.isEmpty() || lastSequence.isEmpty()) {
            sendIdentify(connection);
            return;
        }
        connection.sendTextAndAwait(GatewayProtocol.encodeResume(
                mapper, tokenOf(connection), sessionId.get(), lastSequence.get()));
        LOG.info("Discord gateway: sent RESUME.");
    }

    /**
     * Start the heartbeat virtual thread at {@code intervalMillis}: send {@code op 1} with the last
     * sequence (from the atomic state) every interval until the connection closes or the thread is
     * interrupted. Any prior heartbeat thread is stopped first. The reference swap is lock-guarded; the
     * loop's sleep and {@code sendTextAndAwait} run outside the lock.
     */
    private void startHeartbeat(WebSocketClientConnection connection, long intervalMillis) {
        Thread next = Thread.ofVirtual().name("discord-heartbeat").unstarted(
                () -> heartbeatLoop(connection, intervalMillis));
        Thread previous;
        heartbeatLock.lock();
        try {
            previous = heartbeatThread;
            heartbeatThread = next;
        } finally {
            heartbeatLock.unlock();
        }
        if (previous != null) {
            previous.interrupt(); // stop the old loop OUTSIDE the lock
        }
        next.start();
        LOG.infof("Discord gateway: heartbeat armed at %d ms.", intervalMillis);
    }

    /** The heartbeat loop body: sleep the interval, then send op 1 with the last seq, repeat. */
    private void heartbeatLoop(WebSocketClientConnection connection, long intervalMillis) {
        try {
            while (!Thread.currentThread().isInterrupted() && connection.isOpen()) {
                Thread.sleep(intervalMillis);
                if (!connection.isOpen()) {
                    return;
                }
                connection.sendTextAndAwait(GatewayProtocol.encodeHeartbeat(mapper, state.lastSequence()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // a normal stop on close/re-arm
        } catch (RuntimeException e) {
            // A failed heartbeat means the gateway is unhealthy (a missed HEARTBEAT_ACK / dead socket).
            // Close the connection so @OnClose hands off to DiscordChannel, which reconnects with backoff.
            LOG.warnf("Discord: heartbeat send failed (%s); closing the connection so the gateway reconnects.",
                    DiscordChannel.redact(e.getMessage()));
            closeForReconnect(connection, "heartbeat send failed");
        }
    }

    /**
     * The gateway connection closed (by us via op 7/op 9, by Discord, by a network drop, or by shutdown).
     * Stop the heartbeat, then hand the close code to {@link DiscordChannel#onConnectionClosed(int)} which
     * owns the reconnect decision (reconnect with backoff vs. stop on shutdown or a fatal 4xxx code).
     */
    @OnClose
    void onClose(WebSocketClientConnection connection) {
        stopHeartbeat();
        int closeCode = closeCodeOf(connection);
        LOG.infof("Discord gateway connection closed (code %d).", closeCode);
        channel.onConnectionClosed(closeCode);
    }

    /** The gateway close code, or {@code 0} when none was reported (an abnormal/network close). */
    private static int closeCodeOf(WebSocketClientConnection connection) {
        var reason = connection.closeReason();
        return reason == null ? 0 : reason.getCode();
    }

    /** Stop the heartbeat thread (lock-guarded swap; interrupt outside the lock). */
    void stopHeartbeat() {
        Thread previous;
        heartbeatLock.lock();
        try {
            previous = heartbeatThread;
            heartbeatThread = null;
        } finally {
            heartbeatLock.unlock();
        }
        if (previous != null) {
            previous.interrupt();
        }
    }

    private static void closeQuietly(WebSocketClientConnection connection, String reason) {
        LOG.infof("Discord gateway: closing (%s).", reason);
        try {
            connection.closeAndAwait();
        } catch (RuntimeException e) {
            LOG.warnf("Discord: error while closing the gateway connection (%s).",
                    DiscordChannel.redact(e.getMessage()));
        }
    }

    /**
     * Close with the {@link #RECONNECT_CLOSE_CODE} application code instead of the default 1000:
     * Discord treats a client close with 1000/1001 as "invalidate the session", which would defeat
     * the op-6 RESUME the imminent reconnect attempts. Used by every close-with-intent-to-resume path
     * (op 7, op 9 resumable, a failed heartbeat); a deliberate shutdown and a non-resumable op 9 keep
     * the default 1000 close via {@link #closeQuietly}.
     */
    private static void closeForReconnect(WebSocketClientConnection connection, String reason) {
        LOG.infof("Discord gateway: closing for reconnect (%s).", reason);
        try {
            connection.closeAndAwait(new CloseReason(RECONNECT_CLOSE_CODE, reason));
        } catch (RuntimeException e) {
            LOG.warnf("Discord: error while closing the gateway connection (%s).",
                    DiscordChannel.redact(e.getMessage()));
        }
    }

    /** The bot token carried on the connection's user data (set by the connector before connect). */
    private static String tokenOf(WebSocketClientConnection connection) {
        return connection.userData().get(
                io.quarkus.websockets.next.UserData.TypedKey.forString(TOKEN_KEY));
    }

    /** The {@code Authorization: Bot <token>} header value for the REST reply path. */
    private static String authorization(WebSocketClientConnection connection) {
        return "Bot " + tokenOf(connection);
    }
}
