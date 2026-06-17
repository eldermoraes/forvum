package ai.forvum.tools.web;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import ai.forvum.tools.web.dto.BraveSearchResponse;
import ai.forvum.tools.web.dto.BraveWebResult;

import java.util.List;
import java.util.Optional;

/**
 * Runs a web search via the Brave Search API ({@code web.search}, scope {@link PermissionScope#WEB_SEARCH};
 * PR-6). READ-only outbound HTTP, so {@link #SPEC} uses the backward-compatible 4-arg {@link ToolSpec}
 * constructor ({@code userConfirmRequired = false}) — deliberately OUT of the P2-14/#39 approval gate. It
 * sits behind the engine's belt + P2-11 RBAC scope gates only.
 *
 * <p>The Brave subscription token is operator config (from {@code tools/web.json} or env/microprofile). With
 * NO key the tool returns a clear "not configured" string and issues NO network call — INERT-by-default, so
 * the CI native no-config smoke is safe. With a key it calls {@link BraveSearchApi} (blocking on the turn's
 * virtual thread) and maps {@code web.results[]} to a compact {@code title / url / description} text block
 * for the model.
 */
public final class WebSearchTool {

    /** The tool this class implements, contributed to the registry by {@code WebToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "web.search",
            "Search the web (Brave Search) and return a list of result titles, URLs and snippets. "
          + "Use to discover pages; follow up with web.fetch to read one.",
            PermissionScope.WEB_SEARCH,
            "{\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"description\":\"the search query\"},"
          + "\"count\":{\"type\":\"integer\",\"description\":\"max results (1-20, default 5)\"}},"
          + "\"required\":[\"query\"]}");

    /** Brave's documented maximum {@code count} for the web-search endpoint. */
    public static final int MAX_COUNT = 20;

    private static final String NOT_CONFIGURED =
            "web search is not configured (set the Brave Search API key via tools/web.json or the "
          + "BRAVE_API_KEY / quarkus.rest-client.\"brave-search\" config).";

    private final BraveSearchApi api;

    public WebSearchTool(BraveSearchApi api) {
        this.api = api;
    }

    /**
     * Search for {@code query}, returning at most {@code count} results (clamped to {@code [1, MAX_COUNT]})
     * as a text block. {@code apiKey} is the resolved Brave token: when absent/blank the tool is inert and
     * returns {@link #NOT_CONFIGURED} without calling the API.
     */
    public String search(String query, int count, Optional<String> apiKey) {
        String key = apiKey.map(String::strip).filter(k -> !k.isBlank()).orElse(null);
        if (key == null) {
            return NOT_CONFIGURED;
        }
        int clamped = Math.max(1, Math.min(MAX_COUNT, count));
        BraveSearchResponse response = api.search(key, query, clamped);
        return format(response);
    }

    /** Map a Brave response to a compact text block. Package-private for direct mapping tests. */
    static String format(BraveSearchResponse response) {
        List<BraveWebResult> results = response == null || response.web() == null
                ? null
                : response.web().results();
        if (results == null || results.isEmpty()) {
            return "no results.";
        }
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (BraveWebResult r : results) {
            if (r == null || r.url() == null || r.url().isBlank()) {
                continue;  // a result with no URL is not actionable; skip it.
            }
            if (n > 0) {
                out.append('\n');
            }
            n++;
            out.append(n).append(". ").append(orEmpty(r.title())).append('\n')
               .append("   ").append(r.url().strip());
            String desc = orEmpty(r.description());
            if (!desc.isBlank()) {
                out.append('\n').append("   ").append(desc.strip());
            }
        }
        return n == 0 ? "no results." : out.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
