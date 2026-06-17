package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Pure unit contract for {@code web.fetch}: the {@link EgressGuard} is consulted before any fetch AND on
 * every redirect hop (B1), the bounded redirect loop refuses an HTTPS→HTTP downgrade and a too-long chain,
 * a relative {@code Location} is re-guarded, the decoded body is returned (truncated to a char cap), and
 * the HTTP fetch pins to the guard-approved literal IP (B2). No network: a redirect-scripting
 * {@link FakeHttpFetcher} records every {@link EgressGuard.Approved} it was asked to fetch and replays a
 * scripted response per hop.
 */
class WebFetchToolTest {

    /**
     * A fake fetcher that replays scripted single-hop responses (so the redirect loop lives in
     * {@link WebFetchTool}, never here) and records the approval (URI + pinned IP) for each hop. If the
     * script runs out it returns a terminal 200 with the recorded body, so a single-hop test needs no
     * script.
     */
    private static final class FakeHttpFetcher implements HttpFetcher {
        final List<EgressGuard.Approved> requested = new ArrayList<>();
        final Deque<FetchResult> script = new ArrayDeque<>();
        FetchResult terminal = new FetchResult(200, "text/plain; charset=utf-8", "hello world",
                Optional.empty());

        FakeHttpFetcher redirectTo(String location) {
            script.add(new FetchResult(302, "text/html", "", Optional.of(location)));
            return this;
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved) {
            requested.add(approved);
            return script.isEmpty() ? terminal : script.poll();
        }

        URI lastRequestedUri() {
            return requested.get(requested.size() - 1).uri();
        }
    }

    @Test
    void specIsReadOnlyAndNotConfirmGated() {
        assertEquals("web.fetch", WebFetchTool.SPEC.name());
        assertEquals(PermissionScope.WEB_FETCH, WebFetchTool.SPEC.requiredScope());
        assertFalse(WebFetchTool.SPEC.userConfirmRequired(),
                "web.fetch is READ-only outbound HTTP: it is deliberately OUT of the #39 approval gate");
        assertTrue(WebFetchTool.SPEC.parametersJsonSchema().contains("\"url\""));
    }

    @Test
    void fetchReturnsDecodedBody() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        String body = tool.fetch("https://example.com/page");

        assertEquals("hello world", body);
        assertEquals(URI.create("https://example.com/page"), fetcher.lastRequestedUri(),
                "the fetcher is called with the egress-checked URI");
    }

    @Test
    void fetchTruncatesToCharCap() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.terminal = new HttpFetcher.FetchResult(200, "text/plain", "0123456789ABCDEF",
                Optional.empty());
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 10);

        String body = tool.fetch("https://example.com/big");

        assertTrue(body.startsWith("0123456789"), "the body is truncated to the char cap, got: " + body);
        assertTrue(body.contains("truncated"), "a truncation marker tells the model output was cut");
    }

    @Test
    void fetchRefusesInternalTargetBeforeAnyCall() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        assertThrows(EgressDeniedException.class, () -> tool.fetch("http://169.254.169.254/latest"),
                "the egress guard rejects an SSRF target before the fetcher is touched");
        assertTrue(fetcher.requested.isEmpty(), "no fetch is attempted for a denied target");
    }

    // --- B1: redirect re-validation ---------------------------------------------------------------

    @Test
    void redirectToMetadataIsBlockedAndInternalHopNeverSent() {
        // An http start so the IMDS block isolates the egress GUARD (not the HTTPS→HTTP downgrade refusal).
        FakeHttpFetcher fetcher = new FakeHttpFetcher()
                .redirectTo("http://169.254.169.254/latest/meta-data");   // hop 1 → IMDS
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        assertThrows(EgressDeniedException.class, () -> tool.fetch("http://8.8.8.8/start"),
                "a redirect to the metadata endpoint must be denied (the guard is re-run on the hop)");
        // The public start was fetched (1 hop), but the internal IMDS target was NEVER sent.
        assertEquals(1, fetcher.requested.size(), "only the first hop was sent");
        assertEquals(URI.create("http://8.8.8.8/start"), fetcher.requested.get(0).uri());
    }

    @Test
    void redirectToLoopbackIsBlocked() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher().redirectTo("http://127.0.0.1:6379/");
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        assertThrows(EgressDeniedException.class, () -> tool.fetch("http://8.8.8.8/start"),
                "a redirect to loopback must be denied");
        assertEquals(1, fetcher.requested.size(), "the loopback hop is never sent");
    }

    @Test
    void redirectGuardIsLoadBearing_publicRedirectIsFollowed() {
        // The green-for-wrong-reason guard [M4]: this benign chain MUST be followed (the loop works), so
        // the blocked-redirect tests above prove the GUARD denies, not the loop refusing all redirects.
        FakeHttpFetcher fetcher = new FakeHttpFetcher()
                .redirectTo("https://1.1.1.1/landing");                  // public → public
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        String body = tool.fetch("https://8.8.8.8/start");

        assertEquals("hello world", body, "a benign public→public redirect is followed to the body");
        assertEquals(2, fetcher.requested.size(), "both hops were sent");
        assertEquals(URI.create("https://1.1.1.1/landing"), fetcher.requested.get(1).uri());
    }

    @Test
    void httpsToHttpDowngradeRedirectIsDenied() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher()
                .redirectTo("http://1.1.1.1/landing");                  // https → http downgrade
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        EgressDeniedException ex = assertThrows(EgressDeniedException.class,
                () -> tool.fetch("https://8.8.8.8/start"),
                "an HTTPS→HTTP downgrade redirect must be denied (the refusal NORMAL gave for free)");
        assertTrue(ex.getMessage().toLowerCase().contains("downgrade"), ex.getMessage());
        assertEquals(1, fetcher.requested.size(), "the downgraded hop is never sent");
    }

    @Test
    void relativeLocationIsResolvedAgainstCurrentAndReGuarded() {
        // A relative Location must resolve against the current hop's URI and then be re-guarded. Here the
        // relative path is benign → followed; the resolved absolute is what was fetched.
        FakeHttpFetcher fetcher = new FakeHttpFetcher().redirectTo("/landing/page");
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        String body = tool.fetch("https://8.8.8.8/a/b");

        assertEquals("hello world", body);
        assertEquals(URI.create("https://8.8.8.8/landing/page"), fetcher.requested.get(1).uri(),
                "the relative Location was resolved against the current URI");
    }

    @Test
    void tooManyRedirectsIsDenied() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        // MAX_REDIRECTS + 1 public hops to exceed the cap; each Location stays public so only the cap trips.
        for (int i = 0; i <= WebFetchTool.MAX_REDIRECTS; i++) {
            fetcher.redirectTo("https://8.8.8.8/hop" + i);
        }
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        EgressDeniedException ex = assertThrows(EgressDeniedException.class,
                () -> tool.fetch("https://8.8.8.8/start"),
                "exceeding the redirect cap must be denied");
        assertTrue(ex.getMessage().toLowerCase().contains("too many redirects"), ex.getMessage());
    }

    // --- B2: the HTTP fetch carries the guard-approved pinned IP ----------------------------------

    @Test
    void httpFetchCarriesThePinnedAddress() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        WebFetchTool tool = new WebFetchTool(fetcher, new EgressGuard(false), 1000);

        tool.fetch("http://8.8.8.8/x");

        EgressGuard.Approved sent = fetcher.requested.get(0);
        assertNotNull(sent.pinnedAddress(),
                "the HTTP fetch is handed the guard-approved literal IP to connect to (B2 rebind close)");
        assertEquals("8.8.8.8", sent.pinnedAddress().getHostAddress());
    }
}
