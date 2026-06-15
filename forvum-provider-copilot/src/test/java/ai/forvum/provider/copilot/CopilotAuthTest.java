package ai.forvum.provider.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.provider.copilot.CopilotAuth.CopilotToken;
import ai.forvum.provider.copilot.CopilotAuth.DeviceCode;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link CopilotAuth} over a scripted {@link CopilotHttp} fake (no live GitHub): the device-code request,
 * the poll loop (authorization_pending → access_token; slow_down; expired/denied; overall expiry), the
 * Copilot-token exchange (Bearer header, missing-token guard), {@code proxy-ep} → base-URL derivation, and
 * the seconds-vs-ms {@code expires_at} parsing. Deterministic: a no-op sleeper + a controllable clock.
 */
class CopilotAuthTest {

    /** A {@link CopilotHttp} returning queued responses per URL and recording the headers it saw. */
    private static final class FakeHttp implements CopilotHttp {
        final Deque<Resp> postResponses = new ArrayDeque<>();
        final Deque<Resp> getResponses = new ArrayDeque<>();
        Map<String, String> lastGetHeaders;
        int postCalls;

        @Override
        public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
            postCalls++;
            return postResponses.poll();
        }

        @Override
        public Resp get(String url, Map<String, String> headers) {
            lastGetHeaders = headers;
            return getResponses.poll();
        }
    }

    private static CopilotAuth auth(FakeHttp http, AtomicLong clock) {
        return new CopilotAuth(http, millis -> { /* no real sleep */ }, clock::get);
    }

    @Test
    void requestsADeviceCode() {
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200,
                "{\"device_code\":\"DC\",\"user_code\":\"WXYZ-1234\",\"verification_uri\":"
              + "\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}"));

        DeviceCode dc = auth(http, new AtomicLong(0)).requestDeviceCode();

        assertEquals("DC", dc.deviceCode());
        assertEquals("WXYZ-1234", dc.userCode());
        assertEquals("https://github.com/login/device", dc.verificationUri());
        assertEquals(900, dc.expiresInSeconds());
        assertEquals(5, dc.intervalSeconds());
    }

    @Test
    void pollRetriesOnAuthorizationPendingThenReturnsTheToken() {
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200, "{\"error\":\"authorization_pending\"}"));
        http.postResponses.add(new CopilotHttp.Resp(200, "{\"error\":\"slow_down\"}"));
        http.postResponses.add(new CopilotHttp.Resp(200, "{\"access_token\":\"gho_TOKEN\",\"token_type\":\"bearer\"}"));

        String token = auth(http, new AtomicLong(0)).pollForAccessToken("DC", 10, 1_000_000);

        assertEquals("gho_TOKEN", token);
        assertEquals(3, http.postCalls, "polled through pending + slow_down to the token");
    }

    @Test
    void pollThrowsOnExpiredOrDeniedAndOnOverallExpiry() {
        FakeHttp expired = new FakeHttp();
        expired.postResponses.add(new CopilotHttp.Resp(200, "{\"error\":\"expired_token\"}"));
        assertThrows(CopilotAuthException.class,
                () -> auth(expired, new AtomicLong(0)).pollForAccessToken("DC", 1, 1_000_000));

        FakeHttp denied = new FakeHttp();
        denied.postResponses.add(new CopilotHttp.Resp(200, "{\"error\":\"access_denied\"}"));
        assertThrows(CopilotAuthException.class,
                () -> auth(denied, new AtomicLong(0)).pollForAccessToken("DC", 1, 1_000_000));

        // Clock already past the deadline → never even polls, throws expiry.
        FakeHttp past = new FakeHttp();
        assertThrows(CopilotAuthException.class,
                () -> auth(past, new AtomicLong(5_000)).pollForAccessToken("DC", 1, 1_000));
    }

    @Test
    void exchangesTheCopilotTokenWithABearerHeaderAndDerivesTheBaseUrl() {
        FakeHttp http = new FakeHttp();
        http.getResponses.add(new CopilotHttp.Resp(200,
                "{\"token\":\"tid=abc;proxy-ep=proxy.enterprise.githubcopilot.com;exp=123\","
              + "\"expires_at\":1893456000}"));

        CopilotToken ct = auth(http, new AtomicLong(0)).exchangeCopilotToken("gho_TOKEN");

        assertTrue(ct.token().startsWith("tid=abc;"), "the raw Copilot token is returned");
        assertEquals("https://api.enterprise.githubcopilot.com", ct.baseUrl(),
                "proxy.* host maps to api.*");
        assertEquals(1893456000L * 1000L, ct.expiresAtMs(), "unix seconds → ms");
        assertEquals("Bearer gho_TOKEN", http.lastGetHeaders.get("Authorization"));
        assertEquals(CopilotAuth.EDITOR_VERSION, http.lastGetHeaders.get("Editor-Version"));
    }

    @Test
    void exchangeRejectsAMissingToken() {
        FakeHttp http = new FakeHttp();
        http.getResponses.add(new CopilotHttp.Resp(200, "{\"expires_at\":1893456000}"));
        assertThrows(CopilotAuthException.class,
                () -> auth(http, new AtomicLong(0)).exchangeCopilotToken("gho_TOKEN"));
    }

    @Test
    void deriveApiBaseUrlHandlesProxyEpDefaultAndMissing() {
        assertEquals("https://api.foo.githubcopilot.com",
                CopilotAuth.deriveApiBaseUrl("a=1;proxy-ep=proxy.foo.githubcopilot.com;b=2"));
        // No proxy-ep → default.
        assertEquals(CopilotAuth.DEFAULT_API_BASE_URL, CopilotAuth.deriveApiBaseUrl("a=1;b=2"));
        assertEquals(CopilotAuth.DEFAULT_API_BASE_URL, CopilotAuth.deriveApiBaseUrl(null));
        // Already a bare host (no proxy. prefix) is kept as the api host.
        assertEquals("https://api.individual.githubcopilot.com",
                CopilotAuth.deriveApiBaseUrl("proxy-ep=api.individual.githubcopilot.com"));
    }

    @Test
    void parseExpiresAtAcceptsSecondsStringsAndMillis() {
        com.fasterxml.jackson.databind.node.LongNode secs =
                com.fasterxml.jackson.databind.node.LongNode.valueOf(1_700_000_000L);
        assertEquals(1_700_000_000L * 1000L, CopilotAuth.parseExpiresAtMs(secs));
        com.fasterxml.jackson.databind.node.LongNode ms =
                com.fasterxml.jackson.databind.node.LongNode.valueOf(1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, CopilotAuth.parseExpiresAtMs(ms), "already-ms is left as-is");
        com.fasterxml.jackson.databind.node.TextNode str =
                com.fasterxml.jackson.databind.node.TextNode.valueOf("1700000000");
        assertEquals(1_700_000_000L * 1000L, CopilotAuth.parseExpiresAtMs(str));
    }

    @Test
    void httpErrorStatusesAreReportedNotSwallowed() {
        FakeHttp dc = new FakeHttp();
        dc.postResponses.add(new CopilotHttp.Resp(503, "oops"));
        assertThrows(CopilotAuthException.class, () -> auth(dc, new AtomicLong(0)).requestDeviceCode());

        FakeHttp poll = new FakeHttp();
        poll.postResponses.add(new CopilotHttp.Resp(500, "oops"));
        assertThrows(CopilotAuthException.class,
                () -> auth(poll, new AtomicLong(0)).pollForAccessToken("DC", 1, 1_000_000));

        FakeHttp ex = new FakeHttp();
        ex.getResponses.add(new CopilotHttp.Resp(401, "unauthorized"));
        assertThrows(CopilotAuthException.class, () -> auth(ex, new AtomicLong(0)).exchangeCopilotToken("gh"));
    }

    @Test
    void deviceCodeMissingFieldsIsRejected() {
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200, "{\"user_code\":\"X\"}")); // no device_code/uri
        assertThrows(CopilotAuthException.class, () -> auth(http, new AtomicLong(0)).requestDeviceCode());
    }

    @Test
    void pollOnAnUnknownErrorFailsFast() {
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200, "{\"error\":\"unsupported_grant_type\"}"));
        assertThrows(CopilotAuthException.class,
                () -> auth(http, new AtomicLong(0)).pollForAccessToken("DC", 1, 1_000_000));
    }

    @Test
    void deriveApiBaseUrlHandlesSchemePrefixedAndInvalidProxyEp() {
        // proxy-ep already carrying a scheme is parsed as-is (the scheme branch).
        assertEquals("https://api.q.githubcopilot.com",
                CopilotAuth.deriveApiBaseUrl("proxy-ep=https://proxy.q.githubcopilot.com;x=1"));
        // A proxy-ep that yields no host falls back to the default (the null-host / catch branch).
        assertEquals(CopilotAuth.DEFAULT_API_BASE_URL, CopilotAuth.deriveApiBaseUrl("proxy-ep=:::::"));
    }

    @Test
    void textFieldVariantsAreTreatedAsAbsent() {
        // device_code present-but-null, user_code present-but-numeric, verification_uri present-but-blank
        // → all three read as absent → missing-fields rejection (exercises the text() null/type/blank arms).
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200,
                "{\"device_code\":null,\"user_code\":123,\"verification_uri\":\"  \"}"));
        assertThrows(CopilotAuthException.class, () -> auth(http, new AtomicLong(0)).requestDeviceCode());
    }

    @Test
    void parseExpiresAtRejectsMissingAndNonNumeric() {
        assertThrows(CopilotAuthException.class, () -> CopilotAuth.parseExpiresAtMs(null));
        assertThrows(CopilotAuthException.class, () -> CopilotAuth.parseExpiresAtMs(
                com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        assertThrows(CopilotAuthException.class, () -> CopilotAuth.parseExpiresAtMs(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("not-a-number")));
    }

    @Test
    void malformedJsonBodyIsReportedCleanly() {
        FakeHttp http = new FakeHttp();
        http.postResponses.add(new CopilotHttp.Resp(200, "{ not json"));
        assertThrows(CopilotAuthException.class, () -> auth(http, new AtomicLong(0)).requestDeviceCode());
    }

    @Test
    void ideHeadersCarryEditorAndUserAgentAndOptionalApiVersion() {
        Map<String, String> withVersion = CopilotAuth.ideHeaders(true);
        assertEquals(CopilotAuth.EDITOR_VERSION, withVersion.get("Editor-Version"));
        assertEquals(CopilotAuth.USER_AGENT, withVersion.get("User-Agent"));
        assertEquals(CopilotAuth.GITHUB_API_VERSION, withVersion.get("X-Github-Api-Version"));
        assertTrue(List.of(CopilotAuth.ideHeaders(false).keySet().toArray())
                .stream().noneMatch(k -> k.equals("X-Github-Api-Version")), "no api version when not asked");
    }
}
