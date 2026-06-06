package ai.forvum.channel.telegram;

import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.channel.telegram.dto.TelegramMessage;
import ai.forvum.channel.telegram.dto.TelegramUpdate;
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
 * Maps one Telegram {@link TelegramUpdate} to a turn: {@link TelegramMessage} → {@link ChannelMessage} →
 * {@link ChannelTurnDriver#dispatch} (the engine's {@code TurnService} at runtime), rendering each
 * {@link AgentEvent} back to the user via {@code sendMessage} (ULTRAPLAN §5.5, the long-poll path's core;
 * shared by webhook mode per Risk #8). The same {@code render} arms as the web channel's {@code
 * ChatSocket}: only a {@link TokenDelta} (the reply) and an {@link ErrorEvent} (a failed turn) produce
 * text; the terminal {@link Done} and tool-lifecycle events render to nothing.
 *
 * <p><strong>{@code allowedUserIds} enforcement.</strong> Before any turn is dispatched, the sending
 * user is checked against the {@link Spec}: a disallowed user gets a friendly refusal sent back to their
 * chat, the attempt is audited at WARN, and NO turn runs (the refusal never reaches the agent runtime).
 * This is the M17 Verify "refuses other users with a friendly message".
 */
@ApplicationScoped
public class UpdateProcessor {

    private static final Logger LOG = Logger.getLogger(UpdateProcessor.class);

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "telegram";

    /** The friendly refusal sent to a Telegram user not in {@code allowedUserIds}. */
    static final String REFUSAL_MESSAGE =
            "Sorry, you are not authorized to use this assistant.";

    @Inject
    ChannelTurnDriver turns;

    /**
     * Process one update: dispatch a turn for an allowed user (streaming the reply back), or send the
     * friendly refusal to a disallowed user. Updates without a text message (service messages, edits,
     * non-text content) are skipped silently. Each {@code sendMessage} is isolated so a failed send for
     * one update never aborts the poll loop; a failed turn surfaces as an {@code ErrorEvent} reply.
     *
     * @param update  the inbound update
     * @param spec    the resolved channel config (for {@code allowedUserIds})
     * @param api     the bot API client
     * @param baseUrl the per-invocation base URL embedding the bot token
     */
    public void process(TelegramUpdate update, Spec spec, TelegramBotApi api, String baseUrl) {
        TelegramMessage message = update.message();
        if (message == null || message.text() == null || message.from() == null
                || message.chat() == null) {
            return; // not a text message from a user — nothing to drive a turn with
        }

        long userId = message.from().id();
        long chatId = message.chat().id();

        if (!spec.isUserAllowed(userId)) {
            LOG.warnf("Telegram: refused unauthorized user id %d (chat %d); not in allowedUserIds %s.",
                    userId, chatId, spec.allowedUserIds());
            send(api, baseUrl, chatId, REFUSAL_MESSAGE);
            return;
        }

        ChannelMessage inbound =
                new ChannelMessage(CHANNEL_ID, Long.toString(userId), message.text(), Instant.now());
        turns.dispatch(inbound, event -> {
            String rendered = render(event);
            if (rendered != null && !rendered.isEmpty()) {
                send(api, baseUrl, chatId, rendered);
            }
        });
    }

    /** Send one outbound message, logging (not propagating) a failure so the poll loop survives. */
    private static void send(TelegramBotApi api, String baseUrl, long chatId, String text) {
        try {
            api.sendMessage(baseUrl, chatId, text);
        } catch (RuntimeException e) {
            // Do NOT log the raw throwable: the bot token is embedded in the sendMessage URL path, so a
            // REST-client exception message/stack would leak the secret. Log a redacted message instead.
            LOG.warnf("Telegram: failed to send a message to chat %d (%s).",
                    chatId, TelegramChannel.redact(e.getMessage()));
        }
    }

    /**
     * Render an {@link AgentEvent} to the text the Telegram user receives, or an empty string to send
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the web
     * channel's {@code ChatSocket.render}: v0.1 (streaming Option B) emits only the {@link TokenDelta}
     * reply (the terminal {@link Done} repeats it, so it is skipped) and an {@link ErrorEvent}'s message;
     * the tool-lifecycle events are not surfaced. Package-private so {@code TelegramRenderTest} can cover
     * every arm.
     */
    // Intentionally byte-identical to forvum-channel-web's ChatSocket.render (module isolation forbids a
    // shared type). The exhaustive, default-less switch makes a new AgentEvent arm a compile error in
    // BOTH channels, so the two renderers cannot silently drift.
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
