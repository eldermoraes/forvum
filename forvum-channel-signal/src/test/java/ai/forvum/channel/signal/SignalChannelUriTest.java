package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The pure URL helpers ({@link SignalChannel#eventsUri}/{@link SignalChannel#normalizeBaseUrl}): the
 * events URI appends {@code /api/v1/events} with a URL-encoded {@code account} query; base URLs are
 * trimmed, scheme-defaulted to {@code http://}, and stripped of trailing slashes (mirroring OpenClaw's
 * signal client normalization).
 */
class SignalChannelUriTest {

    @Test
    void eventsUriAppendsThePathAndEncodesTheAccount() {
        assertEquals("http://localhost:8080/api/v1/events?account=%2B15559990000",
                SignalChannel.eventsUri("http://localhost:8080", "+15559990000"),
                "the E.164 plus sign must be percent-encoded");
    }

    @Test
    void eventsUriOmitsTheQueryWithoutAnAccount() {
        assertEquals("http://localhost:8080/api/v1/events",
                SignalChannel.eventsUri("http://localhost:8080", null));
        assertEquals("http://localhost:8080/api/v1/events",
                SignalChannel.eventsUri("http://localhost:8080", "  "));
    }

    @Test
    void trailingSlashesDoNotDoubleThePath() {
        assertEquals("http://localhost:8080/api/v1/events",
                SignalChannel.eventsUri("http://localhost:8080///", null));
    }

    @Test
    void aSchemeLessBaseUrlDefaultsToHttp() {
        assertEquals("http://localhost:8080", SignalChannel.normalizeBaseUrl("localhost:8080"));
        assertEquals("https://daemon.example", SignalChannel.normalizeBaseUrl(" https://daemon.example/ "));
    }

    @Test
    void anUppercaseSchemeIsNotDoublePrefixed() {
        assertEquals("HTTP://localhost:8080", SignalChannel.normalizeBaseUrl("HTTP://localhost:8080"),
                "scheme detection is case-insensitive, so no http:// is prepended");
    }
}
