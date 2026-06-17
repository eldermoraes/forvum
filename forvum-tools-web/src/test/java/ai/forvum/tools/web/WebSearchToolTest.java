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
import java.util.Optional;

/**
 * Pure unit contract for {@code web.search}: result-to-text mapping, the count clamp, and the inert
 * "not configured" message when no Brave key is present (NO network call). A hand-written
 * {@link FakeBraveApi} records the arguments it was called with.
 */
class WebSearchToolTest {

    /** A fake Brave client returning a canned response and recording its call arguments. */
    private static final class FakeBraveApi implements BraveSearchApi {
        boolean called = false;
        String lastKey;
        String lastQuery;
        int lastCount;
        BraveSearchResponse next = new BraveSearchResponse(new BraveWebResults(List.of(
                new BraveWebResult("First", "https://a.example/1", "first snippet"),
                new BraveWebResult("Second", "https://b.example/2", "second snippet"))));

        @Override
        public BraveSearchResponse search(String apiKey, String query, int count) {
            called = true;
            lastKey = apiKey;
            lastQuery = query;
            lastCount = count;
            return next;
        }
    }

    @Test
    void specIsReadOnlyAndNotConfirmGated() {
        assertEquals("web.search", WebSearchTool.SPEC.name());
        assertEquals(PermissionScope.WEB_SEARCH, WebSearchTool.SPEC.requiredScope());
        assertFalse(WebSearchTool.SPEC.userConfirmRequired(),
                "web.search is READ-only: it is deliberately OUT of the #39 approval gate");
        assertTrue(WebSearchTool.SPEC.parametersJsonSchema().contains("\"query\""));
        assertTrue(WebSearchTool.SPEC.parametersJsonSchema().contains("\"count\""));
    }

    @Test
    void mapsResultsToCompactText() {
        FakeBraveApi api = new FakeBraveApi();
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("rust async", 5, Optional.of("BSA-key"));

        assertTrue(api.called);
        assertEquals("BSA-key", api.lastKey);
        assertEquals("rust async", api.lastQuery);
        assertEquals(5, api.lastCount);
        assertTrue(out.contains("First"), out);
        assertTrue(out.contains("https://a.example/1"), out);
        assertTrue(out.contains("first snippet"), out);
        assertTrue(out.contains("Second"), out);
    }

    @Test
    void returnsNotConfiguredWhenNoKeyAndDoesNotCall() {
        FakeBraveApi api = new FakeBraveApi();
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("anything", 5, Optional.empty());

        assertFalse(api.called, "no Brave key → no network call");
        assertTrue(out.toLowerCase().contains("not configured"), out);
        assertTrue(out.toLowerCase().contains("brave"), out);
    }

    @Test
    void blankKeyIsTreatedAsNotConfigured() {
        FakeBraveApi api = new FakeBraveApi();
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("anything", 5, Optional.of("   "));

        assertFalse(api.called);
        assertTrue(out.toLowerCase().contains("not configured"), out);
    }

    @Test
    void countIsClampedToAValidRange() {
        FakeBraveApi api = new FakeBraveApi();
        WebSearchTool tool = new WebSearchTool(api);

        tool.search("q", 0, Optional.of("k"));
        assertTrue(api.lastCount >= 1, "count below 1 is clamped up");

        tool.search("q", 9999, Optional.of("k"));
        assertTrue(api.lastCount <= WebSearchTool.MAX_COUNT, "count is clamped to the API maximum");
    }

    @Test
    void handlesEmptyResultsGracefully() {
        FakeBraveApi api = new FakeBraveApi();
        api.next = new BraveSearchResponse(null);
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("nothing here", 5, Optional.of("k"));
        assertTrue(out.toLowerCase().contains("no results"), out);
    }

    @Test
    void nullResultListIsHandled() {
        FakeBraveApi api = new FakeBraveApi();
        api.next = new BraveSearchResponse(new BraveWebResults(null));
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("nothing", 5, Optional.of("k"));
        assertTrue(out.toLowerCase().contains("no results"), out);
    }

    @Test
    void mappingSkipsResultsWithNoUrl() {
        List<BraveWebResult> results = new ArrayList<>();
        results.add(new BraveWebResult("Has URL", "https://x.example", "ok"));
        results.add(new BraveWebResult("No URL", null, "skipme"));
        FakeBraveApi api = new FakeBraveApi();
        api.next = new BraveSearchResponse(new BraveWebResults(results));
        WebSearchTool tool = new WebSearchTool(api);

        String out = tool.search("q", 5, Optional.of("k"));
        assertTrue(out.contains("Has URL"), out);
        assertFalse(out.contains("skipme"), "a result with no URL is not surfaced");
    }
}
