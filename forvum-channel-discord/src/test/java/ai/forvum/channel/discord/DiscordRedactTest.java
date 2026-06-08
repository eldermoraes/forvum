package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code DiscordChannel.redact} masks a Discord bot token wherever a {@code Bot <token>} header value
 * might echo into a log-bound string (e.g. a REST-client exception). Guards against the secret leaking,
 * and is null-safe.
 */
class DiscordRedactTest {

    private static final String TOKEN = "MTk4NjIyNDgzNDcxOTI1MjQ4.GH1y.secretSuffix_-value";

    @Test
    void redactsABotTokenHeaderValue() {
        String message = "401 Unauthorized for header Authorization: Bot " + TOKEN;
        String redacted = DiscordChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "the token must not survive redaction");
        assertEquals("401 Unauthorized for header Authorization: Bot <redacted>", redacted);
    }

    @Test
    void redactsAllBotTokenOccurrences() {
        String message = "Bot " + TOKEN + " then again Bot " + TOKEN;
        String redacted = DiscordChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "no token occurrence may survive");
        assertEquals("Bot <redacted> then again Bot <redacted>", redacted);
    }

    @Test
    void leavesTokenFreeMessagesUnchanged() {
        assertEquals("connection reset", DiscordChannel.redact("connection reset"));
    }

    @Test
    void isNullSafe() {
        assertNull(DiscordChannel.redact(null));
    }
}
