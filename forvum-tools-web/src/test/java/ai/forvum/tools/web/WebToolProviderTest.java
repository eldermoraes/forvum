package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.tools.web.dto.BraveSearchResponse;
import ai.forvum.tools.web.dto.BraveWebResult;
import ai.forvum.tools.web.dto.BraveWebResults;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatch + integration contract for {@link WebToolProvider#invoke(String, Map)} (M18 Option A): the
 * provider self-dispatches a tool call by name to the {@code WebFetchTool} / {@code WebSearchTool} logic
 * with no reflection, building the {@link EgressGuard} and selecting the search backend from the live
 * {@code tools/web.json} spec. The engine's {@code ToolExecutor} gates permission + audits; this test
 * exercises the in-provider dispatch directly against fakes (no network).
 */
class WebToolProviderTest {

    /** A fake fetcher that echoes the requested URI for web.fetch and returns a DDG page for web.search. */
    private static final class FakeHttpFetcher implements HttpFetcher {
        String ddgBody = TestFixtures.load("ddg-results.html");
        boolean ddgCalled = false;

        @Override
        public FetchResult get(EgressGuard.Approved approved) {
            // web.fetch path: echo the URI so the caller can assert it.
            return new FetchResult(200, "text/plain", "body of " + approved.uri(), Optional.empty());
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved, Map<String, String> headers) {
            // web.search (DDG backend) path: 2-arg override is used, so this is the search.
            ddgCalled = true;
            return new FetchResult(200, "text/html", ddgBody, Optional.empty());
        }
    }

    private static final class FakeBraveApi implements BraveSearchApi {
        String lastKey;
        boolean called = false;
        @Override
        public BraveSearchResponse search(String apiKey, String query, int count) {
            called = true;
            lastKey = apiKey;
            return new BraveSearchResponse(new BraveWebResults(List.of(
                    new BraveWebResult("R", "https://r.example", "snip"))));
        }
    }

    private WebToolProvider providerWith(WebToolConfig.Spec spec) {
        WebToolProvider provider = new WebToolProvider();
        provider.fetcher = new FakeHttpFetcher();
        provider.braveApi = new FakeBraveApi();
        provider.config = new WebToolConfig() {
            @Override
            public Spec read() {
                return spec;
            }
        };
        return provider;
    }

    @Test
    void contributesTwoReadOnlyTools() {
        WebToolProvider provider = new WebToolProvider();
        assertEquals("web", provider.extensionId());
        List<ToolSpec> tools = provider.tools();
        assertEquals(2, tools.size());
        assertTrue(tools.contains(WebFetchTool.SPEC));
        assertTrue(tools.contains(WebSearchTool.SPEC));
    }

    @Test
    void invokeWebFetchDispatchesThroughEgressGuard() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of()));

        String out = provider.invoke("web.fetch", Map.of("url", "https://example.com/p"));
        assertTrue(out.contains("https://example.com/p"), out);
    }

    @Test
    void invokeWebFetchToInternalIsRefused() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of()));

        assertThrows(EgressDeniedException.class,
                () -> provider.invoke("web.fetch", Map.of("url", "http://127.0.0.1/secret")),
                "strict egress blocks loopback on the invoke path");
    }

    @Test
    void invokeWebFetchToInternalAllowedWhenOptedIn() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), true, Set.of()));

        // allowPrivateNetwork=true: the egress guard permits the loopback target (the fake fetcher answers).
        String out = provider.invoke("web.fetch", Map.of("url", "http://127.0.0.1/ok"));
        assertTrue(out.contains("127.0.0.1"), out);
    }

    @Test
    void invokeWebSearchDefaultsToDuckDuckGo() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of()));

        String out = provider.invoke("web.search", Map.of("query", "rust async"));
        assertTrue(((FakeHttpFetcher) provider.fetcher).ddgCalled, "the keyless default reaches DuckDuckGo");
        assertFalse(((FakeBraveApi) provider.braveApi).called, "Brave is not called with no key");
        assertTrue(out.contains("Tokio"), out);
    }

    @Test
    void invokeWebSearchUsesConfiguredKey() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.of("CFG-KEY"), false, Set.of()));

        String out = provider.invoke("web.search", Map.of("query", "q"));
        assertTrue(out.contains("R"), out);
        assertEquals("CFG-KEY", ((FakeBraveApi) provider.braveApi).lastKey);
    }

    @Test
    void invokeWebSearchBraveBackendWithNoKeyReturnsMessageAndDoesNotCall() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of(), Optional.of("brave")));

        String out = provider.invoke("web.search", Map.of("query", "q"));
        assertFalse(((FakeBraveApi) provider.braveApi).called, "no key → no Brave call");
        assertFalse(((FakeHttpFetcher) provider.fetcher).ddgCalled, "config-shaped: no DDG call either");
        assertTrue(out.contains("braveApiKey"), out);
    }

    @Test
    void invokeUnknownToolThrows() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of()));

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("web.crawl", Map.of("url", "x")),
                "a name this provider does not contribute is a programming error");
    }

    @Test
    void invokeMissingRequiredArgThrows() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(Optional.empty(), false, Set.of()));

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("web.fetch", Map.of()),
                "a missing required argument is rejected");
    }
}
