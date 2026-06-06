package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code TelegramChannel.redact} masks the bot token embedded in a Telegram Bot API URL path
 * ({@code .../bot<TOKEN>/...}) before it reaches the logs, and is null-safe. Guards the security fix that
 * stops a REST-client exception message from leaking the secret.
 */
class TelegramRedactTest {

    private static final String TOKEN = "123456:AAEhBOweik6ad9r_secret";

    @Test
    void redactsTheTokenSegmentInAUrl() {
        String message = "Connection refused: https://api.telegram.org/bot" + TOKEN + "/getUpdates";
        String redacted = TelegramChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "the token must not survive redaction");
        assertEquals(
                "Connection refused: https://api.telegram.org/bot<redacted>/getUpdates", redacted);
    }

    @Test
    void redactsAllTokenSegments() {
        String message = "/bot" + TOKEN + "/getUpdates then /bot" + TOKEN + "/sendMessage";
        String redacted = TelegramChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "no token occurrence may survive");
        assertEquals("/bot<redacted>/getUpdates then /bot<redacted>/sendMessage", redacted);
    }

    @Test
    void leavesTokenFreeMessagesUnchanged() {
        assertEquals("read timed out", TelegramChannel.redact("read timed out"));
    }

    @Test
    void isNullSafe() {
        assertNull(TelegramChannel.redact(null));
    }
}
