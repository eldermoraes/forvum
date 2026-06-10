package ai.forvum.channel.slack;

import ai.forvum.channel.slack.SlackSocketProtocol.AckOnly;
import ai.forvum.channel.slack.SlackSocketProtocol.Connected;
import ai.forvum.channel.slack.SlackSocketProtocol.Dispatch;
import ai.forvum.channel.slack.SlackSocketProtocol.Ignored;
import ai.forvum.channel.slack.SlackSocketProtocol.Reaction;
import ai.forvum.channel.slack.SlackSocketProtocol.Reconnect;
import ai.forvum.channel.slack.dto.SocketEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The Slack Socket Mode client endpoint over {@code quarkus-websockets-next} CLIENT mode (the
 * native-clean transport the Discord channel already proved). Connected by {@link SlackChannel} to the
 * TEMPORARY {@code wss://} URL minted by {@code apps.connections.open}, it implements the frame flow:
 *
 * <ol>
 *   <li><strong>hello</strong> → the socket is live; the channel resets its reconnect backoff. No
 *       client-side handshake reply and no application-level heartbeat: Socket Mode keep-alive is
 *       WebSocket ping/pong, handled by the transport (unlike Discord's op-1 loop).</li>
 *   <li><strong>events_api</strong> → acknowledge the envelope FIRST ({@code { "envelope_id": ... }},
 *       Slack's ~3 s deadline — a turn can take far longer), then hand a message event to
 *       {@link SlackMessageProcessor}, which drives the turn and posts the reply via
 *       {@code chat.postMessage}.</li>
 *   <li><strong>disconnect</strong> → close the connection; {@code @OnClose} then hands off to
 *       {@link SlackChannel#onConnectionClosed(int)}, which reconnects with exponential backoff through
 *       a FRESH {@code apps.connections.open} (a Socket Mode URL is never reused) unless the channel is
 *       shutting down.</li>
 * </ol>
 *
 * <p><strong>Concurrency (CLAUDE.md §3.8).</strong> Inbound frames run {@code @RunOnVirtualThread} so
 * handling (the turn, the blocking REST reply) blocks on a virtual thread, never the event loop. The
 * endpoint holds no mutable state at all — no lock anywhere (Socket Mode needs no heartbeat thread).
 *
 * <p>{@code @WebSocketClient} endpoints are CDI beans; this one is the default {@code @Singleton} (one
 * Socket Mode connection per process), so its collaborators are injected. The bot token rides each
 * connection's user data (set by the connector before connect) — never a field, never logged.
 *
 * <p>The endpoint path is EMPTY (not {@code "/"}): websockets-next builds the dial URI by raw string
 * concatenation {@code baseUri + path}, and the minted Socket Mode URL already carries its own path AND
 * query ({@code wss://wss-....slack.com/link/?ticket=...}) — appending even a {@code "/"} would corrupt
 * the trailing query parameter. The empty path also keeps this client endpoint distinct from the Discord
 * gateway's {@code "/"} (client endpoints must have unique paths at build time).
 */
@WebSocketClient(path = "")
public class SlackSocketEndpoint {

    private static final Logger LOG = Logger.getLogger(SlackSocketEndpoint.class);

    /** Per-connection user-data key carrying the bot token (set by the connector before connect). */
    static final String BOT_TOKEN_KEY = "forvum.slack.botToken";

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    SlackMessageProcessor processor;

    @Inject
    SlackChannelConfig config;

    @Inject
    SlackChannel channel;

    @Inject
    @RestClient
    SlackRestClient rest;

    @OnOpen
    void onOpen(WebSocketClientConnection connection) {
        LOG.info("Slack Socket Mode connection opened; awaiting hello.");
    }

    /**
     * Handle one inbound Socket Mode text frame on a virtual thread: parse →
     * {@link SlackSocketProtocol#decide} → act on the {@link Reaction}. A parse/handle failure for one
     * frame is logged (redacted) and swallowed so a single bad frame never kills the socket.
     */
    @OnTextMessage
    @RunOnVirtualThread
    void onText(WebSocketClientConnection connection, String frame) {
        SocketEnvelope envelope;
        try {
            envelope = SlackSocketProtocol.parse(mapper, frame);
        } catch (RuntimeException e) {
            LOG.warnf("Slack: dropping an unparseable Socket Mode frame (%s).",
                    SlackChannel.redact(e.getMessage()));
            return;
        }
        act(connection, SlackSocketProtocol.decide(mapper, envelope));
    }

    /** Apply a decided {@link Reaction} to the live connection (the only IO-performing step). */
    private void act(WebSocketClientConnection connection, Reaction reaction) {
        switch (reaction) {
            case Connected ignored -> {
                channel.onConnected(); // a live socket restarts the reconnect-backoff schedule
                LOG.info("Slack Socket Mode: hello received; connection established.");
            }
            case Dispatch dispatch -> {
                ack(connection, dispatch.envelopeId());
                process(connection, dispatch);
            }
            case AckOnly ackOnly -> ack(connection, ackOnly.envelopeId());
            case Reconnect reconnect -> closeQuietly(connection,
                    "Slack requested a reconnect (disconnect: " + reconnect.reason() + ")");
            case Ignored ignored -> { /* an unhandled frame; nothing to do */ }
        }
    }

    /**
     * Drive the turn for one dispatched message event, isolated so an unexpected processor/config
     * failure (e.g. a malformed {@code channels/slack.json} read mid-edit) is logged and dropped rather
     * than escaping the frame handler — websockets-next would otherwise CLOSE the socket on an uncaught
     * exception (the M16 lesson).
     */
    private void process(WebSocketClientConnection connection, Dispatch dispatch) {
        try {
            processor.process(dispatch.message(), config.read(), rest, authorization(connection));
        } catch (RuntimeException e) {
            LOG.warnf("Slack: failed to process envelope %s (%s).",
                    dispatch.envelopeId(), SlackChannel.redact(e.getMessage()));
        }
    }

    /** Send one acknowledgment, logging (not propagating) a failure so the frame handler survives. */
    private void ack(WebSocketClientConnection connection, String envelopeId) {
        try {
            connection.sendTextAndAwait(SlackSocketProtocol.encodeAck(mapper, envelopeId));
        } catch (RuntimeException e) {
            // A failed ack means Slack will redeliver the envelope (retry_attempt > 0) — log and move on.
            LOG.warnf("Slack: failed to ack envelope %s (%s).",
                    envelopeId, SlackChannel.redact(e.getMessage()));
        }
    }

    /**
     * The Socket Mode connection closed (by us honoring a {@code disconnect} frame, by Slack, by a
     * network drop, or by shutdown). Hand the close code to
     * {@link SlackChannel#onConnectionClosed(int)}, which owns the reconnect decision.
     */
    @OnClose
    void onClose(WebSocketClientConnection connection) {
        int closeCode = closeCodeOf(connection);
        LOG.infof("Slack Socket Mode connection closed (code %d).", closeCode);
        channel.onConnectionClosed(closeCode);
    }

    /** The close code, or {@code 0} when none was reported (an abnormal/network close). */
    private static int closeCodeOf(WebSocketClientConnection connection) {
        var reason = connection.closeReason();
        return reason == null ? 0 : reason.getCode();
    }

    /**
     * The {@code Bearer <bot token>} authorization for the reply path, read from the connection's user
     * data (set by the connector before connect — never a field, so the secret's lifetime tracks the
     * socket's).
     */
    private static String authorization(WebSocketClientConnection connection) {
        return "Bearer " + connection.userData().get(TypedKey.forString(BOT_TOKEN_KEY));
    }

    private static void closeQuietly(WebSocketClientConnection connection, String reason) {
        LOG.infof("Slack Socket Mode: closing (%s).", reason);
        try {
            connection.closeAndAwait();
        } catch (RuntimeException e) {
            LOG.warnf("Slack: error while closing the Socket Mode connection (%s).",
                    SlackChannel.redact(e.getMessage()));
        }
    }
}
