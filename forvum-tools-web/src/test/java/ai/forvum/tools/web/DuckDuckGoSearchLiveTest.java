package ai.forvum.tools.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * A LIVE keyless search against the real DuckDuckGo HTML endpoint (#192), driving the production
 * {@link JdkHttpFetcher} + {@link DuckDuckGoBackend}. Default-OFF: {@code @Tag("live")}, which the module
 * pom excludes; opt in with {@code -DexcludedGroups= -Dgroups=live} (nightly / manual). It is the drift
 * alarm for the HTML scrape and the only place the browser-UA HTTP request is exercised end-to-end; a
 * datacenter IP may hit a bot challenge, so it is deliberately not in any per-PR gate.
 */
@Tag("live")
class DuckDuckGoSearchLiveTest {

    @Test
    void keylessSearchReturnsResults() {
        DuckDuckGoBackend backend = new DuckDuckGoBackend(new JdkHttpFetcher());

        List<SearchResult> results = backend.search("wikipedia", 5);

        assertFalse(results.isEmpty(), "a keyless DuckDuckGo search returns at least one result");
        SearchResult first = results.get(0);
        assertFalse(first.title().isBlank(), "a result has a non-blank title");
        assertTrue(first.url().startsWith("http"), "a result URL is an absolute http(s) URL: " + first.url());
    }
}
