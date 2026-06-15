package ai.forvum.channel.whatsapp;

import ai.forvum.channel.whatsapp.WhatsAppChannelConfig.Spec;
import ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage;
import ai.forvum.channel.whatsapp.dto.SendMessageRequest;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Maps one inbound WhatsApp {@link InboundMessage} to a turn: {@link InboundMessage} → {@link ChannelMessage}
 * → {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the sender as a Graph API {@code send} through {@link GraphApi}. There is NO
 * outbound channel-send API on the SPI — the channel collects the reply and sends it over its own
 * transport, mirroring the Telegram/Signal channels. The same {@code render} arms as the other channels:
 * only a {@link TokenDelta} (the reply) and an {@link ErrorEvent} (a failed turn) produce text; the
 * terminal {@link Done} and tool-lifecycle events render to nothing.
 *
 * <p><strong>{@code allowedUserIds} enforcement.</strong> Before any turn is dispatched, the sender's
 * {@code wa_id} is checked against the {@link Spec}: a disallowed sender gets a friendly refusal sent
 * back, the attempt is audited at WARN <em>without</em> logging the sender id or the allow-list members
 * (both are phone numbers — operator PII; only the authorized-set SIZE is logged), and NO turn runs.
 *
 * <p><strong>No self-echo.</strong> The webhook delivers only inbound user messages ({@code messages}),
 * never the business's own sends, so (unlike Signal) there is no own-account reply loop to guard.
 */
@ApplicationScoped
public class MessageProcessor {

    private static final Logger LOG = Logger.getLogger(MessageProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "whatsapp";

    /** The friendly refusal sent to a WhatsApp sender not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE = "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /**
     * Process one inbound text message: dispatch a turn for an allowed sender (sending the reply back),
     * or send the friendly refusal to a disallowed sender. Each send is isolated so a failed send never
     * aborts the webhook worker; a failed turn surfaces as an {@code ErrorEvent} reply.
     */
    public void process(InboundMessage message, Spec spec, GraphApi api) {
        if (!spec.isSenderAllowed(message.from())) {
            // Audit WITHOUT the sender id or the allow-list members — both are phone numbers (operator
            // contact graph), so logging them raw is a PII disclosure; only the authorized SIZE.
            LOG.warn(refusalAudit(spec.allowedUserIds().size()));
            send(api, spec, message.from(), REFUSAL_MESSAGE);
            return;
        }
        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, message.from(), message.text(), Instant.now());
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                send(api, spec, message.from(), rendered);
            }
        });
    }

    /**
     * Send one outbound text as a Graph API {@code send}, logging (not propagating) a failure so the
     * webhook worker survives. A Graph error envelope (HTTP 200 with {@code error}) is logged by code +
     * the API's message; the user's content is never logged (length only). The access token rides the
     * {@code Authorization} header, so a thrown exception's URL cannot leak it; the message is still
     * redacted defensively.
     */
    private void send(GraphApi api, Spec spec, String to, String text) {
        try {
            JsonNode response = api.send(spec.apiVersion(), spec.phoneNumberId().orElseThrow(),
                    "Bearer " + spec.accessToken().orElseThrow(), SendMessageRequest.text(to, text));
            JsonNode error = response == null ? null : response.get("error");
            if (error != null && !error.isNull()) {
                LOG.warnf("WhatsApp: Graph send returned an error (code %s: %s) for a %d-char message.",
                        error.path("code").asText("?"), error.path("message").asText("?"), text.length());
            }
        } catch (RuntimeException e) {
            LOG.warnf("WhatsApp: failed to send a %d-char message (%s).",
                    text.length(), redact(e.getMessage()));
        }
    }

    /**
     * The non-identifying refusal audit line logged at WARN ("denied + audited", CLAUDE.md §11): the
     * authorized-set SIZE only, NEVER the rejected sender id or the allow-list members (phone numbers —
     * operator PII). Package-private + pure so a test pins that no identifying material can reach the logs.
     */
    static String refusalAudit(int authorizedCount) {
        return "WhatsApp: refused an unauthorized sender (not in allowedUserIds; "
                + authorizedCount + " authorized id(s)).";
    }

    /**
     * Redact a {@code Bearer <token>} occurrence from a log-bound string. The Graph access token rides the
     * {@code Authorization} header (not the URL), but a thrown rest-client message could conceivably echo
     * it — masked so it never reaches the logs. Null-safe.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("(?i)Bearer\\s+\\S+", "Bearer <redacted>");
    }

    /**
     * Render an {@link AgentEvent} to the text the WhatsApp sender receives, or an empty string to send
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), byte-identical to the
     * Telegram/Discord/Signal/web renderers (module isolation forbids a shared type — the default-less
     * switch makes a new {@code AgentEvent} arm a compile error in every channel, so they cannot drift).
     */
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
