package ai.forvum.channel.telegram;

import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.sdk.ChannelSender;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The Telegram implementation of the outbound {@link ChannelSender} SPI (#188): it reuses the M17
 * blocking {@code sendMessage} REST path the inbound long-poll loop already drives, so an
 * engine-originated message (a {@code message.send} tool call, a cron's {@code last}/{@code
 * explicit-to} delivery) reaches a live Telegram chat through exactly the transport the channel owns.
 *
 * <p>The target is a Telegram chat id in decimal form; a blank target falls back to the operator's
 * {@code "defaultChatId"} in {@code channels/telegram.json}, then to the sole {@code allowedUserIds}
 * entry when exactly one is configured (a private-bot chat id equals the user id). The config is
 * re-read per send (the M17 on-demand model), so an operator edit takes effect without restart.
 *
 * <p>Security ([M17]): the bot token rides the request URL path, so a REST-client failure is never
 * logged or rethrown raw — the message is redacted via {@link TelegramChannel#redact}.
 */
@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    private static final Logger LOG = Logger.getLogger(TelegramChannelSender.class);

    @Inject
    @RestClient
    TelegramBotApi api;

    @Inject
    TelegramChannelConfig config;

    TelegramChannelSender() {
    }

    /** Package-private constructor wiring explicit collaborators — for tests. */
    TelegramChannelSender(TelegramBotApi api, TelegramChannelConfig config) {
        this.api = api;
        this.config = config;
    }

    @Override
    public String extensionId() {
        return TelegramChannelConfig.CHANNEL_ID;
    }

    @Override
    public boolean send(String target, String text) {
        Spec spec = config.read();
        if (!spec.enabled() || spec.botToken().isEmpty()) {
            LOG.warn("Telegram outbound send skipped: channel disabled or no botToken in "
                    + "channels/telegram.json.");
            return false;
        }
        Optional<Long> chatId = resolveChatId(target, spec);
        if (chatId.isEmpty()) {
            LOG.warn("Telegram outbound send skipped: no target given and no \"defaultChatId\" (or a "
                    + "single allowedUserIds entry) in channels/telegram.json to fall back to.");
            return false;
        }
        String baseUrl = TelegramChannel.API_ROOT + spec.botToken().get();
        try {
            api.sendMessage(baseUrl, chatId.get(), text);
            return true;
        } catch (RuntimeException e) {
            // The bot token is embedded in the sendMessage URL path — never propagate/log it raw ([M17]).
            throw new IllegalStateException("Telegram outbound send to chat " + chatId.get()
                    + " failed (" + TelegramChannel.redact(e.getMessage()) + ").");
        }
    }

    /**
     * Resolve the destination chat id: an explicit decimal {@code target} wins (a malformed one is a
     * caller error, rejected); a blank target falls back to {@code defaultChatId}, then to the sole
     * allowed user id. Package-private for tests.
     */
    static Optional<Long> resolveChatId(String target, Spec spec) {
        if (target != null && !target.isBlank()) {
            try {
                return Optional.of(Long.parseLong(target.strip()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unknown Telegram target '" + target
                        + "': a Telegram target must be a chat id in decimal form.");
            }
        }
        if (spec.defaultChatId().isPresent()) {
            return spec.defaultChatId();
        }
        if (spec.allowedUserIds().size() == 1) {
            return Optional.of(spec.allowedUserIds().iterator().next());
        }
        return Optional.empty();
    }
}
