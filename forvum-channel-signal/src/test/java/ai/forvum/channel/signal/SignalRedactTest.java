package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code SignalChannel.redact} masks the {@code account} query value (the operator's phone number)
 * embedded in the events-stream URL before an exception message reaches the logs, and is null-safe.
 * The Signal channel has no secret token (the daemon is local and unauthenticated), so the account
 * parameter is the only URL-borne sensitive value.
 */
class SignalRedactTest {

    @Test
    void redactsTheAccountQueryValueInAUrl() {
        String message = "Connection refused: http://localhost:8080/api/v1/events?account=%2B15559990000";
        String redacted = SignalChannel.redact(message);

        assertFalse(redacted.contains("15559990000"), "the account must not survive redaction");
        assertEquals("Connection refused: http://localhost:8080/api/v1/events?account=<redacted>",
                redacted);
    }

    @Test
    void redactsEveryAccountOccurrenceAndStopsAtDelimiters() {
        String message = "GET ?account=+1555&x=1 then ?account=+1666 failed";
        String redacted = SignalChannel.redact(message);

        assertEquals("GET ?account=<redacted>&x=1 then ?account=<redacted> failed", redacted);
    }

    @Test
    void leavesAccountFreeMessagesUnchanged() {
        assertEquals("read timed out", SignalChannel.redact("read timed out"));
    }

    @Test
    void isNullSafe() {
        assertNull(SignalChannel.redact(null));
    }
}
