package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code SlackChannel.redact} masks BOTH Slack token families ({@code xoxb-} bot, {@code xapp-}
 * app-level) wherever a {@code Bearer <token>} header value might echo into a log-bound string (e.g. a
 * REST-client exception). Guards against the secrets leaking, and is null-safe.
 */
class SlackRedactTest {

    // Built by concatenation so the file never contains a literal matching Slack's real token
    // format (GitHub push protection rejects pushes carrying realistic xoxb-/xapp- literals).
    private static final String BOT_TOKEN = "xoxb-" + "1234567890-0987654321-AbCdEfGhIjKlMnOpQrStUvWx";
    private static final String APP_TOKEN = "xapp-" + "1-A0123456789-1234567890123-abcdef0123456789";

    @Test
    void redactsABotTokenHeaderValue() {
        String message = "401 invalid_auth for header Authorization: Bearer " + BOT_TOKEN;
        String redacted = SlackChannel.redact(message);

        assertFalse(redacted.contains(BOT_TOKEN), "the bot token must not survive redaction");
        assertEquals("401 invalid_auth for header Authorization: Bearer <redacted>", redacted);
    }

    @Test
    void redactsAnAppTokenOccurrence() {
        String message = "apps.connections.open failed with Bearer " + APP_TOKEN;
        String redacted = SlackChannel.redact(message);

        assertFalse(redacted.contains(APP_TOKEN), "the app token must not survive redaction");
        assertEquals("apps.connections.open failed with Bearer <redacted>", redacted);
    }

    @Test
    void redactsAllTokenOccurrences() {
        String message = BOT_TOKEN + " then " + APP_TOKEN + " again " + BOT_TOKEN;
        String redacted = SlackChannel.redact(message);

        assertFalse(redacted.contains(BOT_TOKEN), "no bot-token occurrence may survive");
        assertFalse(redacted.contains(APP_TOKEN), "no app-token occurrence may survive");
        assertEquals("<redacted> then <redacted> again <redacted>", redacted);
    }

    @Test
    void redactsTheTicketQueryOfAMintedSocketUrl() {
        // A failed WebSocket connect can echo the dial URI — the apps.connections.open-minted wss URL
        // carrying the single-use ?ticket=... credential — into the exception message (the M17 lesson:
        // never log a secret-bearing URL). The ticket parameter must not survive redaction.
        String message = "Connect failed: wss://wss-primary.slack.com/link/?ticket=8f1a-secret-2c&app_id=A1"
                + " (handshake 401)";
        String redacted = SlackChannel.redact(message);

        assertFalse(redacted.contains("8f1a-secret-2c"), "the ticket credential must not survive");
        assertEquals("Connect failed: wss://wss-primary.slack.com/link/?ticket=<redacted>&app_id=A1"
                + " (handshake 401)", redacted);
    }

    @Test
    void leavesTokenFreeMessagesUnchanged() {
        assertEquals("connection reset", SlackChannel.redact("connection reset"));
    }

    @Test
    void isNullSafe() {
        assertNull(SlackChannel.redact(null));
    }
}
