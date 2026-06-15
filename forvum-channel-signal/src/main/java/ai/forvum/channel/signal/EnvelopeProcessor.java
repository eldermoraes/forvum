package ai.forvum.channel.signal;

import ai.forvum.channel.signal.SignalChannelConfig.Spec;
import ai.forvum.channel.signal.SignalEvents.TextMessage;
import ai.forvum.channel.signal.dto.JsonRpcRequest;
import ai.forvum.channel.signal.dto.JsonRpcResponse;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps one inbound Signal {@link TextMessage} to a turn: {@link TextMessage} → {@link ChannelMessage} →
 * {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the sender as a JSON-RPC {@code send} through {@link SignalRpcApi}. There
 * is NO outbound channel-send API on the SPI — the channel itself collects the reply and sends it over
 * its own transport, mirroring the Telegram channel's {@code UpdateProcessor}. The same {@code render}
 * arms as the Telegram/Discord/web channels: only a {@link TokenDelta} (the reply) and an
 * {@link ErrorEvent} (a failed turn) produce text; the terminal {@link Done} and tool-lifecycle events
 * render to nothing.
 *
 * <p><strong>{@code allowedUserIds} enforcement.</strong> Before any turn is dispatched, the sender is
 * checked against the {@link Spec} by BOTH ids signal-cli can stamp on an envelope ({@code sourceNumber}
 * and {@code sourceUuid}, plus the {@code replyTo} fallback): a disallowed sender gets a friendly
 * refusal sent back, the attempt is audited at WARN, and NO turn runs (the refusal never reaches the
 * agent runtime).
 *
 * <p><strong>Logging discipline.</strong> Message CONTENT is never logged at INFO/WARN — only lengths
 * and the daemon's own error code/message; a transport exception is logged with the account query
 * redacted ({@link SignalChannel#redact}).
 */
@ApplicationScoped
public class EnvelopeProcessor {

    private static final Logger LOG = Logger.getLogger(EnvelopeProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "signal";

    /** The friendly refusal sent to a Signal sender not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE =
            "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /** Monotonic JSON-RPC request ids (the daemon echoes them; uniqueness per process suffices). */
    private final AtomicLong rpcIds = new AtomicLong();

    /**
     * Process one inbound text message: dispatch a turn for an allowed sender (sending the reply back),
     * or send the friendly refusal to a disallowed sender. Each send is isolated so a failed send for
     * one event never aborts the stream-consuming loop; a failed turn surfaces as an {@code ErrorEvent}
     * reply.
     *
     * @param message the parsed inbound text message
     * @param spec    the resolved channel config (for {@code allowedUserIds})
     * @param api     the daemon's JSON-RPC client
     * @param baseUrl the per-invocation daemon base URL
     * @param account the Signal account the daemon sends as ({@code params.account})
     */
    public void process(TextMessage message, Spec spec, SignalRpcApi api, String baseUrl,
                        String account) {
        if (isOwnMessage(message, account)) {
            // The daemon can surface the account's OWN sends as a plain dataMessage (a "Note to Self",
            // or a linked-device transcript) — NOT always wrapped in syncMessage, so the parse-time
            // syncMessage filter does not catch it. Replying would send the bot's message back to
            // itself and re-ingest it: an unbounded self-reply loop. Drop it before the allow-list
            // check (an empty allow-list would otherwise "allow" the bot's own number) and any reply.
            LOG.debug("Signal: ignoring the bot's own message (self-echo); no turn, no reply.");
            return;
        }
        if (!spec.isSenderAllowed(message.sourceNumber(), message.sourceUuid(), message.replyTo())) {
            // Audit the refusal WITHOUT the sender id or the allow-list members: for Signal both are
            // phone numbers / source UUIDs (the operator's contact graph), so logging them raw is a
            // PII disclosure to anyone with log access — only the authorized-set SIZE is logged.
            LOG.warn(refusalAudit(spec.allowedUserIds().size()));
            send(api, baseUrl, account, message.replyTo(), REFUSAL_MESSAGE);
            return;
        }

        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, message.replyTo(), message.text(), Instant.now());
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                send(api, baseUrl, account, message.replyTo(), rendered);
            }
        });
    }

    /**
     * Whether {@code message} is the bot's OWN account echoed back (self-echo): {@code account} matches
     * any id signal-cli stamps on the envelope ({@code sourceNumber}, {@code sourceUuid}, or the
     * {@code replyTo} fallback). A blank/absent account never matches. Package-private + pure for direct
     * unit testing.
     */
    static boolean isOwnMessage(TextMessage message, String account) {
        if (account == null || account.isBlank()) {
            return false;
        }
        return account.equals(message.sourceNumber())
                || account.equals(message.sourceUuid())
                || account.equals(message.replyTo());
    }

    /**
     * The non-identifying refusal audit line logged at WARN ("denied + audited", CLAUDE.md §11): the
     * authorized-set SIZE only, NEVER the rejected sender id or the allow-list members (for Signal those
     * are phone numbers / UUIDs — operator PII). Package-private + pure so a test pins that no
     * identifying material can structurally reach the logs (mirrors {@link SignalChannel#redact}).
     */
    static String refusalAudit(int authorizedCount) {
        return "Signal: refused an unauthorized sender (not in allowedUserIds; "
                + authorizedCount + " authorized id(s)).";
    }

    /**
     * Send one outbound message as a JSON-RPC {@code send}, logging (not propagating) a failure so the
     * stream-consuming loop survives. A JSON-RPC error envelope (HTTP 200 with {@code error}) is logged
     * by code + the daemon's message; the user's content is never logged (length only).
     */
    private void send(SignalRpcApi api, String baseUrl, String account, String recipient, String text) {
        try {
            JsonRpcResponse response =
                    api.rpc(baseUrl, JsonRpcRequest.send(rpcIds.incrementAndGet(), account, recipient, text));
            if (response != null && response.error() != null) {
                LOG.warnf("Signal: send RPC failed (code %s: %s) for a %d-char message.",
                        response.error().code(), response.error().message(), text.length());
            }
        } catch (RuntimeException e) {
            // Log a redacted message only (no stack): an exception message can echo the request URL,
            // whose account query parameter is the operator's phone number.
            LOG.warnf("Signal: failed to send a %d-char message (%s).",
                    text.length(), SignalChannel.redact(e.getMessage()));
        }
    }

    /**
     * Render an {@link AgentEvent} to the text the Signal sender receives, or an empty string to send
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the Telegram
     * channel's {@code UpdateProcessor.render}: v0.1 (streaming Option B) emits only the
     * {@link TokenDelta} reply (the terminal {@link Done} repeats it, so it is skipped) and an
     * {@link ErrorEvent}'s message; the tool-lifecycle events are not surfaced. Package-private so
     * {@code SignalRenderTest} can cover every arm.
     */
    // Intentionally byte-identical to forvum-channel-telegram's UpdateProcessor.render,
    // forvum-channel-discord's MessageProcessor.render, and forvum-channel-web's ChatSocket.render
    // (module isolation forbids a shared type). The exhaustive, default-less switch makes a new
    // AgentEvent arm a compile error in ALL four channels, so the renderers cannot silently drift.
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
