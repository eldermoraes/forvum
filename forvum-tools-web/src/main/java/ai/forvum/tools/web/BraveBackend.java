package ai.forvum.tools.web;

import ai.forvum.tools.web.dto.BraveSearchResponse;
import ai.forvum.tools.web.dto.BraveWebResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The keyed Brave Search {@code web.search} backend (#192): wraps the untouched {@link BraveSearchApi}
 * REST client + the operator's subscription token and maps Brave's {@code web.results[]} to the common
 * {@link SearchResult} list. Selected when {@code tools/web.json} sets {@code "backend": "brave"} or (with
 * no explicit backend) supplies a {@code braveApiKey} — the precedence lives in {@link WebSearchTool}.
 *
 * <p>The mapping is the response half of the old {@code WebSearchTool.format}: skip a result with no URL
 * (not actionable), carry title/url/description across. The blocking {@code api.search(...)} runs on the
 * turn's virtual thread. A {@code null}/empty web block yields an empty list ("no results.").
 */
final class BraveBackend implements WebSearchBackend {

    private final BraveSearchApi api;
    private final String apiKey;

    BraveBackend(BraveSearchApi api, String apiKey) {
        this.api = api;
        this.apiKey = apiKey;
    }

    @Override
    public List<SearchResult> search(String query, int count) {
        BraveSearchResponse response = api.search(apiKey, query, count);
        List<BraveWebResult> results = response == null || response.web() == null
                ? null
                : response.web().results();
        List<SearchResult> mapped = new ArrayList<>();
        if (results == null) {
            return mapped;
        }
        for (BraveWebResult r : results) {
            if (r == null || r.url() == null || r.url().isBlank()) {
                continue;   // a result with no URL is not actionable; skip it (parity with the old format()).
            }
            mapped.add(new SearchResult(
                    orEmpty(r.title()), r.url().strip(), orEmpty(r.description())));
        }
        return mapped;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
