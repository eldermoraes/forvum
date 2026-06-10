package ai.forvum.channel.matrix;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.MatrixSyncProtocol.InboundMessage;
import ai.forvum.channel.matrix.MatrixSyncProtocol.Invite;
import ai.forvum.channel.matrix.dto.JoinRequest;
import ai.forvum.channel.matrix.dto.SendMessageRequest;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Maps one Matrix {@link InboundMessage} to a turn: {@link InboundMessage} → {@link ChannelMessage} →
 * {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the room via {@code PUT .../send/m.room.message/{txnId}} (ULTRAPLAN §5.5;
 * the {@code render} arms are byte-identical to the Telegram/Discord/web channels'). It also handles a
 * pending room {@link Invite}: auto-join iff the inviter passes {@code allowedUserIds}, else ignore.
 *
 * <p><strong>{@code allowedUserIds} enforcement.</strong> Before any turn is dispatched, the sending
 * user is checked against the {@link Spec}: a disallowed user gets a friendly refusal sent back to the
 * room, the attempt is audited at WARN, and NO turn runs (the refusal never reaches the agent runtime) —
 * the M17 contract.
 */
@ApplicationScoped
public class SyncProcessor {

    private static final Logger LOG = Logger.getLogger(SyncProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "matrix";

    /** The friendly refusal sent to a Matrix user not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE =
            "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /** Per-boot transaction-id source shared by every send (refusals + replies). */
    final TransactionIds txnIds = new TransactionIds();

    /**
     * Process one inbound message: dispatch a turn for an allowed user (sending the reply back to the
     * room), or send the friendly refusal to a disallowed user. Each send is isolated so a failed send
     * for one message never aborts the sync loop; a failed turn surfaces as an {@code ErrorEvent} reply.
     *
     * @param message       the inbound message (already self-filtered by {@link MatrixSyncProtocol})
     * @param spec          the resolved channel config (for {@code allowedUserIds})
     * @param api           the Matrix client
     * @param baseUrl       the per-invocation homeserver base URL
     * @param authorization the {@code Bearer <accessToken>} authorization header value (the secret)
     */
    public void process(InboundMessage message, Spec spec, MatrixClientApi api, String baseUrl,
                        String authorization) {
        if (message == null || message.sender() == null || message.body() == null
                || message.roomId() == null) {
            return; // protocol-layer guarantees, kept defensive — nothing to drive a turn with
        }

        if (!spec.isUserAllowed(message.sender())) {
            LOG.warnf("Matrix: refused unauthorized user %s (room %s); not in allowedUserIds %s.",
                    message.sender(), message.roomId(), spec.allowedUserIds());
            send(api, baseUrl, authorization, message.roomId(), REFUSAL_MESSAGE);
            return;
        }

        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, message.sender(), message.body(), Instant.now());
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                send(api, baseUrl, authorization, message.roomId(), rendered);
            }
        });
    }

    /**
     * Process one pending invite: auto-join when {@link MatrixSyncProtocol#shouldJoin} approves the
     * inviter, else audit-and-ignore. A failed join is logged (redacted) and never aborts the sync loop.
     */
    public void processInvite(Invite invite, Spec spec, MatrixClientApi api, String baseUrl,
                              String authorization) {
        if (invite == null || invite.roomId() == null) {
            return;
        }
        if (!MatrixSyncProtocol.shouldJoin(invite, spec)) {
            LOG.warnf("Matrix: ignoring an invite to room %s from %s; inviter not in allowedUserIds %s.",
                    invite.roomId(), invite.inviter(), spec.allowedUserIds());
            return;
        }
        try {
            api.join(baseUrl, authorization, invite.roomId(), new JoinRequest());
            LOG.infof("Matrix: joined room %s on an invite from %s.", invite.roomId(), invite.inviter());
        } catch (RuntimeException e) {
            // Do NOT log the raw throwable: the access token travels in the Authorization header, so a
            // REST-client exception message/stack could leak the secret. Log a redacted message instead.
            LOG.warnf("Matrix: failed to join room %s (%s).",
                    invite.roomId(), MatrixChannel.redact(e.getMessage()));
        }
    }

    /** Send one outbound message, logging (not propagating) a failure so the sync loop survives. */
    private void send(MatrixClientApi api, String baseUrl, String authorization, String roomId,
                      String text) {
        try {
            api.sendMessage(baseUrl, authorization, roomId, txnIds.next(), SendMessageRequest.text(text));
        } catch (RuntimeException e) {
            // Do NOT log the raw throwable: the access token travels in the Authorization header, so a
            // REST-client exception message/stack could leak the secret. Log a redacted message instead.
            LOG.warnf("Matrix: failed to send a message to room %s (%s).",
                    roomId, MatrixChannel.redact(e.getMessage()));
        }
    }

    /**
     * Render an {@link AgentEvent} to the text the Matrix user receives, or an empty string to send
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the Telegram
     * channel's {@code UpdateProcessor.render}, the Discord channel's {@code MessageProcessor.render},
     * and the web channel's {@code ChatSocket.render}: v0.1 (streaming Option B) emits only the
     * {@link TokenDelta} reply (the terminal {@link Done} repeats it, so it is skipped) and an
     * {@link ErrorEvent}'s message; the tool-lifecycle events are not surfaced. Package-private so
     * {@code MatrixRenderTest} can cover every arm.
     */
    // Intentionally byte-identical to the Telegram/Discord/web renderers (module isolation forbids a
    // shared type). The exhaustive, default-less switch makes a new AgentEvent arm a compile error in
    // ALL channels, so the renderers cannot silently drift.
    static String render(AgentEvent event) {
        return switch (event) {
            case TokenDelta delta -> delta.text();
            case ErrorEvent error -> error.message();
            case Done ignored -> "";
            case ToolInvoked ignored -> "";
            case ToolResult ignored -> "";
            case FallbackTriggered ignored -> "";
        };
    }
}
