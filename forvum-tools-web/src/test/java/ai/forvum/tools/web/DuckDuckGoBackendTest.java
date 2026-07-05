package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contract for the DuckDuckGo backend (#192) against a fake {@link HttpFetcher} (no network). Verifies the
 * request targets the fixed host with the query ENCODED, the browser User-Agent is passed via the 2-arg
 * override, results are sliced to {@code count}, and the failure taxonomy (non-200, challenge, markup
 * drift, redirect cap, internal redirect) is honored. The guarded redirect loop is exercised through a
 * scripted queue of responses.
 */
class DuckDuckGoBackendTest {

    /** A fake fetcher that records the guard-approved request + headers and returns scripted responses. */
    private static final class RecordingFetcher implements HttpFetcher {
        final Deque<HttpFetcher.FetchResult> responses = new ArrayDeque<>();
        EgressGuard.Approved lastApproved;
        Map<String, String> lastHeaders;
        int calls = 0;

        RecordingFetcher(HttpFetcher.FetchResult... scripted) {
            for (HttpFetcher.FetchResult r : scripted) {
                responses.add(r);
            }
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved) {
            return get(approved, Map.of());
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved, Map<String, String> headers) {
            calls++;
            lastApproved = approved;
            lastHeaders = headers;
            if (responses.isEmpty()) {
                throw new AssertionError("fetcher called more times than scripted");
            }
            return responses.poll();
        }
    }

    private static HttpFetcher.FetchResult ok(String body) {
        return new HttpFetcher.FetchResult(200, "text/html", body, Optional.empty());
    }

    private static HttpFetcher.FetchResult redirectTo(String location) {
        return new HttpFetcher.FetchResult(301, "text/html", "", Optional.of(location));
    }

    @Test
    void buildsTheEncodedFixedHostRequestAndPassesTheBrowserUa() {
        RecordingFetcher fetcher = new RecordingFetcher(ok(TestFixtures.load("ddg-results.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        backend.search("rust async & more #1", 5);

        String requested = fetcher.lastApproved.uri().toString();
        assertTrue(requested.startsWith(DuckDuckGoBackend.ENDPOINT + "?q="),
                "the search hits the fixed DDG HTML endpoint: " + requested);
        assertTrue(requested.contains("rust+async") || requested.contains("rust%20async"),
                "the space in the query is encoded: " + requested);
        assertTrue(requested.contains("%26"), "the & in the query is percent-encoded: " + requested);
        assertTrue(requested.contains("%23"), "the # in the query is percent-encoded: " + requested);
        assertEquals(DuckDuckGoBackend.BROWSER_USER_AGENT,
                fetcher.lastHeaders.get("User-Agent"), "the browser UA is passed on the search request");
    }

    @Test
    void slicesResultsToTheRequestedCount() {
        RecordingFetcher fetcher = new RecordingFetcher(ok(TestFixtures.load("ddg-results.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        List<SearchResult> results = backend.search("rust async", 2);
        assertEquals(2, results.size(), "the 3-result fixture is sliced to count=2");
    }

    @Test
    void nonOkStatusThrowsNamingTheStatus() {
        RecordingFetcher fetcher = new RecordingFetcher(
                new HttpFetcher.FetchResult(503, "text/html", "upstream busy", Optional.empty()));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void botChallengeBodyThrowsAnActionableMessage() {
        RecordingFetcher fetcher = new RecordingFetcher(ok(TestFixtures.load("ddg-challenge.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().toLowerCase().contains("challenge"), e.getMessage());
        assertTrue(e.getMessage().contains("tools/web.json"), e.getMessage());
    }

    @Test
    void driftedMarkupThrowsAMarkupDriftMessage() {
        RecordingFetcher fetcher = new RecordingFetcher(ok(TestFixtures.load("ddg-drifted.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().toLowerCase().contains("no parseable results"), e.getMessage());
        assertTrue(e.getMessage().contains("braveApiKey"), e.getMessage());
    }

    @Test
    void genuineNoResultsPageReturnsEmpty() {
        RecordingFetcher fetcher = new RecordingFetcher(ok(TestFixtures.load("ddg-no-results.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        assertTrue(backend.search("asdkjfhaskjdfhqweqwe", 5).isEmpty(),
                "a genuine no-results page returns empty, not an exception");
    }

    @Test
    void followsARedirectThenParses() {
        RecordingFetcher fetcher = new RecordingFetcher(
                redirectTo("https://html.duckduckgo.com/html?q=rust+async&redir=1"),
                ok(TestFixtures.load("ddg-results.html")));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        List<SearchResult> results = backend.search("rust async", 5);
        assertEquals(3, results.size(), "a public-host redirect is followed and the target parsed");
        assertEquals(2, fetcher.calls, "one redirect hop plus the final GET");
    }

    @Test
    void tooManyRedirectsThrows() {
        // 4 redirects exceed the cap of 3.
        RecordingFetcher fetcher = new RecordingFetcher(
                redirectTo("https://html.duckduckgo.com/html?q=q&r=1"),
                redirectTo("https://html.duckduckgo.com/html?q=q&r=2"),
                redirectTo("https://html.duckduckgo.com/html?q=q&r=3"),
                redirectTo("https://html.duckduckgo.com/html?q=q&r=4"),
                ok("<a class=\"result__a\" href=\"https://x/\">x</a>"));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().toLowerCase().contains("too many redirects"), e.getMessage());
    }

    @Test
    void aRedirectToAnInternalAddressIsDeniedByTheGuard() {
        // HTTPS loopback: passes the HTTPS→HTTP downgrade check, so it reaches the egress guard's
        // private-address denial (which runs on every hop before the next fetch).
        RecordingFetcher fetcher = new RecordingFetcher(
                redirectTo("https://127.0.0.1/secret"));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        assertThrows(EgressDeniedException.class, () -> backend.search("q", 5));
    }

    @Test
    void anHttpsToHttpDowngradeRedirectIsRefused() {
        // The search starts on https://html.duckduckgo.com/html, so a redirect to an http target is a
        // downgrade and is refused (before the private-IP check even applies).
        RecordingFetcher fetcher = new RecordingFetcher(redirectTo("http://evil.example/"));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().toLowerCase().contains("downgrade"), e.getMessage());
    }

    @Test
    void aMalformedRedirectLocationIsRefusedWithAnActionableMessage() {
        // URI.resolve throws IllegalArgumentException on an unparseable Location; the backend must turn it
        // into the actionable WebSearchException, not leak the raw internal error (mirrors WebFetchTool).
        RecordingFetcher fetcher = new RecordingFetcher(redirectTo("ht tp://bad location"));
        DuckDuckGoBackend backend = new DuckDuckGoBackend(fetcher);

        WebSearchException e = assertThrows(WebSearchException.class, () -> backend.search("q", 5));
        assertTrue(e.getMessage().toLowerCase().contains("malformed redirect location"), e.getMessage());
    }
}
