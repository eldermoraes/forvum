package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.tools.web.dto.BraveSearchResponse;
import ai.forvum.tools.web.dto.BraveWebResult;
import ai.forvum.tools.web.dto.BraveWebResults;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure unit contract for {@code web.search} (#192): the backend-selection PRECEDENCE (explicit backend >
 * braveApiKey-implies-brave > keyless DuckDuckGo default), config-shaped messages, and the shared result
 * rendering. Both backends are exercised through hand-written fakes (no network): a {@link FakeBraveApi}
 * records the Brave call, and a {@link FakeFetcher} feeds the DuckDuckGo HTML backend a saved fixture.
 */
class WebSearchToolTest {

    /** A fake Brave client returning a canned response and recording its call arguments. */
    private static final class FakeBraveApi implements BraveSearchApi {
        boolean called = false;
        String lastKey;
        int lastCount;
        BraveSearchResponse next = new BraveSearchResponse(new BraveWebResults(List.of(
                new BraveWebResult("Brave First", "https://a.example/1", "first snippet"),
                new BraveWebResult("Brave Second", "https://b.example/2", "second snippet"))));

        @Override
        public BraveSearchResponse search(String apiKey, String query, int count) {
            called = true;
            lastKey = apiKey;
            lastCount = count;
            return next;
        }
    }

    /** A fake fetcher answering with a fixed DuckDuckGo results page and recording that it was called. */
    private static final class FakeFetcher implements HttpFetcher {
        boolean called = false;
        final String body;

        FakeFetcher(String body) {
            this.body = body;
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved) {
            return get(approved, Map.of());
        }

        @Override
        public FetchResult get(EgressGuard.Approved approved, Map<String, String> headers) {
            called = true;
            return new FetchResult(200, "text/html", body, Optional.empty());
        }
    }

    private static String ddgResults() {
        return TestFixtures.load("ddg-results.html");
    }

    private static WebToolConfig.Spec spec(Optional<String> key, Optional<String> backend) {
        return new WebToolConfig.Spec(key, false, Set.of(), backend);
    }

    // ---- SPEC ----

    @Test
    void specIsReadOnlyAndNotConfirmGated() {
        assertEquals("web.search", WebSearchTool.SPEC.name());
        assertEquals(PermissionScope.WEB_SEARCH, WebSearchTool.SPEC.requiredScope());
        assertFalse(WebSearchTool.SPEC.userConfirmRequired(),
                "web.search is READ-only: it is deliberately OUT of the #39 approval gate");
        assertTrue(WebSearchTool.SPEC.parametersJsonSchema().contains("\"query\""));
        assertTrue(WebSearchTool.SPEC.parametersJsonSchema().contains("\"count\""));
    }

    // ---- Precedence ----

    @Test
    void noBackendNoKeyDefaultsToDuckDuckGo() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("rust async", 5, spec(Optional.empty(), Optional.empty()));

        assertTrue(ddg.called, "the keyless default must reach the DuckDuckGo backend");
        assertFalse(brave.called, "Brave must NOT be called when no key is set");
        assertTrue(out.contains("Asynchronous Programming in Rust"), out);
        assertTrue(out.contains("https://rust-lang.github.io/async-book/"), out);
    }

    @Test
    void noBackendWithKeySelectsBrave() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("q", 5, spec(Optional.of("BSA-key"), Optional.empty()));

        assertTrue(brave.called, "a braveApiKey with no explicit backend selects Brave");
        assertFalse(ddg.called, "DuckDuckGo must not be called when Brave is selected");
        assertEquals("BSA-key", brave.lastKey);
        assertTrue(out.contains("Brave First"), out);
    }

    @Test
    void explicitDuckDuckGoBackendOverridesAPresentKey() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("q", 5, spec(Optional.of("BSA-key"), Optional.of("duckduckgo")));

        assertTrue(ddg.called, "an explicit backend overrides the braveApiKey");
        assertFalse(brave.called, "Brave must not be called when backend=duckduckgo is explicit");
        assertTrue(out.contains("Tokio"), out);
    }

    @Test
    void explicitBraveBackendIsCaseInsensitive() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("q", 5, spec(Optional.of("k"), Optional.of("BRAVE")));

        assertTrue(brave.called, "backend value is matched case-insensitively");
        assertFalse(ddg.called);
        assertTrue(out.contains("Brave First"), out);
    }

    @Test
    void braveBackendWithoutKeyReturnsActionableMessageAndNoCall() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("q", 5, spec(Optional.empty(), Optional.of("brave")));

        assertFalse(brave.called, "no key → no Brave call");
        assertFalse(ddg.called, "config-shaped: no network call at all");
        assertTrue(out.contains("braveApiKey"), out);
        assertTrue(out.contains("tools/web.json"), out);
        assertTrue(out.contains("duckduckgo"), out);
    }

    @Test
    void unknownBackendReturnsActionableMessageAndNoCall() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        String out = tool.search("q", 5, spec(Optional.empty(), Optional.of("bing")));

        assertFalse(brave.called);
        assertFalse(ddg.called, "an unrecognized backend makes no network call");
        assertTrue(out.contains("bing"), out);
        assertTrue(out.toLowerCase().contains("not recognized"), out);
        assertTrue(out.contains("duckduckgo"), out);
        assertTrue(out.contains("brave"), out);
    }

    @Test
    void countIsClampedToAValidRangeForBrave() {
        FakeBraveApi brave = new FakeBraveApi();
        FakeFetcher ddg = new FakeFetcher(ddgResults());
        WebSearchTool tool = new WebSearchTool(brave, ddg);

        tool.search("q", 0, spec(Optional.of("k"), Optional.empty()));
        assertTrue(brave.lastCount >= 1, "count below 1 is clamped up");

        tool.search("q", 9999, spec(Optional.of("k"), Optional.empty()));
        assertTrue(brave.lastCount <= WebSearchTool.MAX_COUNT, "count is clamped to the maximum");
    }

    // ---- Rendering (format(List<SearchResult>)) ----

    @Test
    void formatRendersNumberedBlock() {
        String out = WebSearchTool.format(List.of(
                new SearchResult("First", "https://a.example/1", "first snippet"),
                new SearchResult("Second", "https://b.example/2", "second snippet")));

        assertTrue(out.contains("1. First"), out);
        assertTrue(out.contains("https://a.example/1"), out);
        assertTrue(out.contains("first snippet"), out);
        assertTrue(out.contains("2. Second"), out);
    }

    @Test
    void formatOfEmptyIsNoResults() {
        assertTrue(WebSearchTool.format(List.of()).toLowerCase().contains("no results"));
        assertTrue(WebSearchTool.format(null).toLowerCase().contains("no results"));
    }

    @Test
    void formatSkipsResultsWithNoUrl() {
        List<SearchResult> results = new ArrayList<>();
        results.add(new SearchResult("Has URL", "https://x.example", "ok"));
        results.add(new SearchResult("No URL", "", "skipme"));

        String out = WebSearchTool.format(results);
        assertTrue(out.contains("Has URL"), out);
        assertFalse(out.contains("skipme"), "a result with no URL is not surfaced");
    }

    @Test
    void formatOmitsBlankSnippetLine() {
        String out = WebSearchTool.format(List.of(
                new SearchResult("Title only", "https://x.example", "")));
        assertTrue(out.contains("Title only"), out);
        assertTrue(out.contains("https://x.example"), out);
        assertFalse(out.endsWith("   "), "a blank snippet adds no trailing indented line");
    }
}
