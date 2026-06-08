package ai.forvum.channel.discord;

import ai.forvum.channel.discord.DiscordChannelConfig.Spec;
import ai.forvum.channel.discord.dto.CreateMessage;
import ai.forvum.channel.discord.dto.MessageCreate;
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
 * Maps one Discord {@link MessageCreate} to a turn: {@link MessageCreate} → {@link ChannelMessage} →
 * {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the channel via the Discord REST API ({@link DiscordRestClient}). The same
 * {@code render} arms as the Telegram channel's {@code UpdateProcessor} and the web channel's
 * {@code ChatSocket}: only a {@link TokenDelta} (the reply) and an {@link ErrorEvent} (a failed turn)
 * produce text; the terminal {@link Done} and tool-lifecycle events render to nothing.
 *
 * <p><strong>Self/bot + empty filtering.</strong> A message from a bot (including this bot's own replies)
 * or with no text is ignored — never drives a turn. <strong>{@code allowedUserIds} enforcement.</strong>
 * Before any turn is dispatched, the sending user is checked against the {@link Spec}: a disallowed user
 * gets a friendly refusal posted back to their channel, the attempt is audited at WARN, and NO turn runs.
 */
@ApplicationScoped
public class MessageProcessor {

    private static final Logger LOG = Logger.getLogger(MessageProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "discord";

    /** The friendly refusal posted to a Discord user not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE =
            "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /**
     * Process one {@code MESSAGE_CREATE}: dispatch a turn for an allowed human user (posting the reply
     * back), or post the friendly refusal to a disallowed user. Bot/self messages and messages without
     * text are skipped silently. Each REST send is isolated so a failed reply for one message never
     * aborts the gateway; a failed turn surfaces as an {@code ErrorEvent} reply.
     *
     * @param message       the inbound message
     * @param spec          the resolved channel config (for {@code allowedUserIds})
     * @param rest          the Discord REST client
     * @param authorization the {@code Bot <token>} authorization header value (the per-deployment secret)
     */
    public void process(MessageCreate message, Spec spec, DiscordRestClient rest, String authorization) {
        if (message == null || message.author() == null || message.author().bot()
                || message.content() == null || message.content().isEmpty()
                || message.channelId() == null) {
            return; // a bot/self message, an empty message, or a malformed frame — nothing to drive a turn
        }

        long userId;
        try {
            userId = Long.parseLong(message.author().id());
        } catch (NumberFormatException e) {
            LOG.warnf("Discord: ignoring a message from a non-numeric author id %s.", message.author().id());
            return;
        }
        String channelId = message.channelId();

        if (!spec.isUserAllowed(userId)) {
            LOG.warnf("Discord: refused unauthorized user id %d (channel %s); not in allowedUserIds %s.",
                    userId, channelId, spec.allowedUserIds());
            send(rest, authorization, channelId, REFUSAL_MESSAGE);
            return;
        }

        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, Long.toString(userId), message.content(), Instant.now());
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                send(rest, authorization, channelId, rendered);
            }
        });
    }

    /** Post one outbound message, logging (not propagating) a failure so the gateway survives. */
    private static void send(DiscordRestClient rest, String authorization, String channelId, String text) {
        try {
            rest.createMessage(authorization, channelId, new CreateMessage(text));
        } catch (RuntimeException e) {
            // Do NOT log the raw throwable: the bot token travels in the Authorization header, so a
            // REST-client exception message/stack could leak the secret. Log a redacted message instead.
            LOG.warnf("Discord: failed to post a message to channel %s (%s).",
                    channelId, DiscordChannel.redact(e.getMessage()));
        }
    }

    /**
     * Render an {@link AgentEvent} to the text the Discord user receives, or an empty string to post
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the Telegram
     * channel's {@code UpdateProcessor.render} and the web channel's {@code ChatSocket.render}: v0.1
     * (streaming Option B) emits only the {@link TokenDelta} reply (the terminal {@link Done} repeats it,
     * so it is skipped) and an {@link ErrorEvent}'s message; the tool-lifecycle events are not surfaced.
     * Package-private so {@code DiscordRenderTest} can cover every arm.
     */
    // Intentionally byte-identical to forvum-channel-telegram's UpdateProcessor.render and
    // forvum-channel-web's ChatSocket.render (module isolation forbids a shared type). The exhaustive,
    // default-less switch makes a new AgentEvent arm a compile error in ALL three channels, so the
    // renderers cannot silently drift.
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
