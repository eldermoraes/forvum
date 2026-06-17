package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure unit contract for {@code web.fetch}: the {@link EgressGuard} is consulted before any fetch, the
 * decoded body is returned (truncated to a char cap), and the SPEC is the READ-only 4-arg form (NOT in
 * the #39 approval gate). No network: a {@link FakeHttpFetcher} records the URI it was asked to fetch.
 */
class WebFetchToolTest {

    /** A fake fetcher that returns a canned body and records every URI it was asked for. */
    private static final class FakeHttpFetcher implements HttpFetcher {
        final List<URI> requested = new ArrayList<>();
        FetchResult next = new FetchResult(200, "text/plain; charset=utf-8", "hello world");

        @Override
        public FetchResult get(URI uri) {
            requested.add(uri);
            return next;
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
        assertEquals(URI.create("https://example.com/page"), fetcher.requested.get(0),
                "the fetcher is called with the egress-checked URI");
    }

    @Test
    void fetchTruncatesToCharCap() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.next = new HttpFetcher.FetchResult(200, "text/plain", "0123456789ABCDEF");
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
}
