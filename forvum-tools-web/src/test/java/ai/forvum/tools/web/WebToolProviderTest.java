package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ToolSpec;
import ai.forvum.tools.web.dto.BraveSearchResponse;
import ai.forvum.tools.web.dto.BraveWebResult;
import ai.forvum.tools.web.dto.BraveWebResults;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Dispatch + integration contract for {@link WebToolProvider#invoke(String, Map)} (M18 Option A): the
 * provider self-dispatches a tool call by name to the {@code WebFetchTool} / {@code WebSearchTool} logic
 * with no reflection, building the {@link EgressGuard} from the live {@code tools/web.json} spec. The
 * engine's {@code ToolExecutor} gates permission + audits; this test exercises the in-provider dispatch
 * directly against fakes (no network).
 */
class WebToolProviderTest {

    private static final class FakeHttpFetcher implements HttpFetcher {
        EgressGuard.Approved last;
        @Override
        public FetchResult get(EgressGuard.Approved approved) {
            last = approved;
            return new FetchResult(200, "text/plain", "body of " + approved.uri(),
                    java.util.Optional.empty());
        }
    }

    private static final class FakeBraveApi implements BraveSearchApi {
        String lastKey;
        @Override
        public BraveSearchResponse search(String apiKey, String query, int count) {
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
                new WebToolConfig.Spec(java.util.Optional.empty(), false, java.util.Set.of()));

        String out = provider.invoke("web.fetch", Map.of("url", "https://example.com/p"));
        assertTrue(out.contains("https://example.com/p"), out);
    }

    @Test
    void invokeWebFetchToInternalIsRefused() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.empty(), false, java.util.Set.of()));

        assertThrows(EgressDeniedException.class,
                () -> provider.invoke("web.fetch", Map.of("url", "http://127.0.0.1/secret")),
                "strict egress blocks loopback on the invoke path");
    }

    @Test
    void invokeWebFetchToInternalAllowedWhenOptedIn() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.empty(), true, java.util.Set.of()));

        // allowPrivateNetwork=true: the egress guard permits the loopback target (the fake fetcher answers).
        String out = provider.invoke("web.fetch", Map.of("url", "http://127.0.0.1/ok"));
        assertTrue(out.contains("127.0.0.1"), out);
    }

    @Test
    void invokeWebSearchUsesConfiguredKey() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.of("CFG-KEY"), false, java.util.Set.of()));

        String out = provider.invoke("web.search", Map.of("query", "q"));
        assertTrue(out.contains("R"), out);
        assertEquals("CFG-KEY", ((FakeBraveApi) provider.braveApi).lastKey);
    }

    @Test
    void invokeWebSearchInertWithNoKey() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.empty(), false, java.util.Set.of()));

        String out = provider.invoke("web.search", Map.of("query", "q"));
        assertTrue(out.toLowerCase().contains("not configured"), out);
    }

    @Test
    void invokeUnknownToolThrows() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.empty(), false, java.util.Set.of()));

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("web.crawl", Map.of("url", "x")),
                "a name this provider does not contribute is a programming error");
    }

    @Test
    void invokeMissingRequiredArgThrows() {
        WebToolProvider provider = providerWith(
                new WebToolConfig.Spec(java.util.Optional.empty(), false, java.util.Set.of()));

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("web.fetch", Map.of()),
                "a missing required argument is rejected");
    }
}
