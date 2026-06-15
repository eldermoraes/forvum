package ai.forvum.channel.slack;

import ai.forvum.channel.slack.SlackChannelConfig.Spec;
import ai.forvum.channel.slack.dto.ChatPostMessage;
import ai.forvum.channel.slack.dto.ChatPostMessageResponse;
import ai.forvum.channel.slack.dto.MessageEvent;
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
 * Maps one Slack {@link MessageEvent} to a turn: {@link MessageEvent} → {@link ChannelMessage} →
 * {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the conversation via the Slack Web API ({@code chat.postMessage}). The same
 * {@code render} arms as the Telegram/Discord processors and the web channel's {@code ChatSocket}: only
 * a {@link TokenDelta} (the reply) and an {@link ErrorEvent} (a failed turn) produce text; the terminal
 * {@link Done} and tool-lifecycle events render to nothing.
 *
 * <p><strong>Subtype/bot/self + empty filtering</strong> (OpenClaw slack parity, in Forvum's
 * vocabulary): a message with a {@code subtype} (edits, deletions, joins — not a plain user message),
 * with a {@code bot_id} (a bot, including this bot's own replies), without a {@code user}, or with no
 * text is ignored — never drives a turn. <strong>{@code allowedUserIds} enforcement.</strong> Before any
 * turn is dispatched, the sending user is checked against the {@link Spec}: a disallowed user gets a
 * friendly refusal posted back to their conversation, the attempt is audited at WARN, and NO turn runs.
 *
 * <p>Replies are posted in-channel plain ({@code thread_ts} is decoded but not echoed); threaded replies
 * are a documented follow-up.
 */
@ApplicationScoped
public class SlackMessageProcessor {

    private static final Logger LOG = Logger.getLogger(SlackMessageProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "slack";

    /** The friendly refusal posted to a Slack user not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE =
            "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /**
     * Process one message event: dispatch a turn for an allowed human user (posting the reply back), or
     * post the friendly refusal to a disallowed user. Subtyped/bot/self messages and messages without a
     * user, a conversation, or text are skipped silently. Each REST send is isolated so a failed reply
     * for one message never aborts the socket; a failed turn surfaces as an {@code ErrorEvent} reply.
     *
     * @param event         the inbound message event (already structurally classified by
     *                      {@link SlackSocketProtocol})
     * @param spec          the resolved channel config (for {@code allowedUserIds})
     * @param rest          the Slack Web API client
     * @param authorization the {@code Bearer <xoxb- bot token>} header value (the per-deployment secret)
     */
    public void process(MessageEvent event, Spec spec, SlackRestClient rest, String authorization) {
        if (event == null || event.subtype() != null || event.botId() != null
                || event.user() == null || event.user().isBlank()
                || event.channel() == null || event.channel().isBlank()
                || event.text() == null || event.text().isEmpty()) {
            return; // a subtyped/bot/self message, an empty message, or a malformed event — no turn
        }

        String userId = event.user();
        String channelId = event.channel();

        if (!spec.isUserAllowed(userId)) {
            LOG.warnf("Slack: refused unauthorized user id %s (conversation %s); not in allowedUserIds %s.",
                    userId, channelId, spec.allowedUserIds());
            send(rest, authorization, channelId, REFUSAL_MESSAGE);
            return;
        }

        ChannelMessage inbound = new ChannelMessage(CHANNEL_ID, userId, event.text(), Instant.now());
        turns.dispatch(inbound, agentEvent -> {
            String rendered = render(agentEvent);
            if (rendered != null && !rendered.isEmpty()) {
                send(rest, authorization, channelId, rendered);
            }
        });
    }

    /**
     * Post one outbound message, logging (not propagating) a failure so the socket survives. Slack
     * signals most failures as HTTP 200 {@code ok: false} with an {@code error} token (no secret in it),
     * so both the {@code ok: false} arm and a thrown REST exception are logged (redacted) and swallowed.
     */
    private static void send(SlackRestClient rest, String authorization, String channelId, String text) {
        try {
            ChatPostMessageResponse response =
                    rest.postMessage(authorization, new ChatPostMessage(channelId, text));
            if (response == null || !response.ok()) {
                LOG.warnf("Slack: chat.postMessage to conversation %s returned ok=false (%s).",
                        channelId, response == null ? "empty response" : response.error());
            }
        } catch (RuntimeException e) {
            // Do NOT log the raw throwable: the bot token travels in the Authorization header, so a
            // REST-client exception message/stack could leak the secret. Log a redacted message instead.
            LOG.warnf("Slack: failed to post a message to conversation %s (%s).",
                    channelId, SlackChannel.redact(e.getMessage()));
        }
    }

    /**
     * Render an {@link AgentEvent} to the text the Slack user receives, or an empty string to post
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the
     * Telegram/Discord processors and the web channel's {@code ChatSocket.render}: v0.1 (streaming
     * Option B) emits only the {@link TokenDelta} reply (the terminal {@link Done} repeats it, so it is
     * skipped) and an {@link ErrorEvent}'s message; the tool-lifecycle events are not surfaced.
     * Package-private so {@code SlackRenderTest} can cover every arm.
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
