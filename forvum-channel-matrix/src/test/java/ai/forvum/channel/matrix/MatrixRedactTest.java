package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code MatrixChannel.redact} masks the access token before any log line: the {@code Bearer <token>}
 * Authorization-header echo (the form Forvum itself sends) and a defensive {@code access_token=...}
 * query-param echo (the legacy Matrix auth form a proxy/homeserver error text might reflect), and is
 * null-safe. Guards the security rule that stops a REST-client exception message from leaking the
 * secret (the Telegram/Discord redaction precedent).
 */
class MatrixRedactTest {

    private static final String TOKEN = "syt_YWJj_FAkEseCREtTokeN_123456";

    @Test
    void redactsABearerHeaderEcho() {
        String message = "Received: HTTP 401 for request with header Authorization: Bearer " + TOKEN;
        String redacted = MatrixChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "the token must not survive redaction");
        assertEquals("Received: HTTP 401 for request with header Authorization: Bearer <redacted>",
                redacted);
    }

    @Test
    void redactsCaseInsensitively() {
        String redacted = MatrixChannel.redact("bearer " + TOKEN + " rejected");

        assertFalse(redacted.contains(TOKEN));
        assertEquals("Bearer <redacted> rejected", redacted);
    }

    @Test
    void redactsAnAccessTokenQueryParamEcho() {
        String message = "GET https://m.org/_matrix/client/v3/sync?access_token=" + TOKEN + "&timeout=0";
        String redacted = MatrixChannel.redact(message);

        assertFalse(redacted.contains(TOKEN), "no token occurrence may survive");
        assertEquals("GET https://m.org/_matrix/client/v3/sync?access_token=<redacted>&timeout=0",
                redacted);
    }

    @Test
    void leavesTokenFreeMessagesUnchanged() {
        assertEquals("read timed out", MatrixChannel.redact("read timed out"));
        assertEquals("M_UNKNOWN_TOKEN: Invalid access token passed.",
                MatrixChannel.redact("M_UNKNOWN_TOKEN: Invalid access token passed."));
    }

    @Test
    void isNullSafe() {
        assertNull(MatrixChannel.redact(null));
    }
}
