package ai.forvum.tools.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The keyless default {@code web.search} backend (#192): scrapes the DuckDuckGo HTML endpoint
 * ({@code https://html.duckduckgo.com/html?q=...}), so search works with NO API key out of the box.
 *
 * <p><strong>Egress ([TOOLS-WEB]).</strong> Never a naive {@code new HttpClient}: the request goes through
 * the module's hardened path — the URL is composed from the FIXED constant host plus the
 * {@link URLEncoder}-encoded query (so the model-supplied query can only ride as an encoded param, never
 * steer the host), a strict {@link EgressGuard} (default ports; {@code allowPrivateNetwork} is NOT honored
 * — the search host is fixed public, unlike an operator's intranet {@code web.fetch} target) validates it
 * and every redirect hop, and {@link HttpFetcher} carries the UA/timeout/2 MiB-cap/{@code Redirect.NEVER}.
 * A small bounded redirect loop (cap {@link #MAX_REDIRECTS}) mirrors {@link WebFetchTool}, re-checking each
 * hop and refusing an HTTPS→HTTP downgrade. DuckDuckGo HTML serves a browser-shaped client better, so this
 * request (only) sends a browser-like User-Agent (OpenClaw parity); the honest {@code Forvum/...} UA stays
 * on {@code web.fetch} and everywhere else.
 *
 * <p><strong>Failure ({@link WebSearchException}).</strong> A non-200 status, a bot-detection challenge
 * page, or a non-empty page that parses to zero results (markup drift or a silent block) throws with an
 * actionable message; a genuine no-results page returns an empty list. The parsing itself is the pure,
 * fixture-tested {@link DuckDuckGoHtmlParser}.
 */
final class DuckDuckGoBackend implements WebSearchBackend {

    static final String ENDPOINT = "https://html.duckduckgo.com/html";

    /** DuckDuckGo HTML challenges non-browser clients; a browser UA (OpenClaw's) lowers the block rate. */
    static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/122.0.0.0 Safari/537.36";

    /** Bounded redirect hops (defense against loops); small because a search endpoint rarely redirects. */
    static final int MAX_REDIRECTS = 3;

    private final HttpFetcher fetcher;

    DuckDuckGoBackend(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public List<SearchResult> search(String query, int count) {
        String url = ENDPOINT + "?q=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        String html = fetch(url);

        if (DuckDuckGoHtmlParser.isBotChallenge(html)) {
            throw new WebSearchException(
                    "DuckDuckGo returned a bot-detection challenge (the datacenter IP may be rate-limited). "
                  + "Retry later, or configure a keyed backend (e.g. \"backend\": \"brave\" with braveApiKey) "
                  + "in tools/web.json.");
        }

        List<SearchResult> results = DuckDuckGoHtmlParser.parse(html);
        if (results.isEmpty() && !isGenuineNoResults(html)) {
            throw new WebSearchException(
                    "DuckDuckGo returned a page with no parseable results — the markup may have changed or "
                  + "the request was blocked. Configure a keyed backend (e.g. \"backend\": \"brave\" with "
                  + "braveApiKey) in tools/web.json.");
        }
        return results.size() > count ? results.subList(0, count) : results;
    }

    /**
     * Fetch {@code url} through the guarded path, following up to {@link #MAX_REDIRECTS} redirects with the
     * egress guard re-run on every hop and an HTTPS→HTTP downgrade refused. A non-200 (non-redirect) status
     * is a {@link WebSearchException}. Returns the decoded body of the final 200 response.
     */
    private String fetch(String url) {
        EgressGuard guard = new EgressGuard(false);
        EgressGuard.Approved approved = guard.check(url);
        int redirects = 0;
        while (true) {
            EgressGuard.Approved pinned = guard.recheck(approved.uri());
            HttpFetcher.FetchResult result =
                    fetcher.get(pinned, Map.of("User-Agent", BROWSER_USER_AGENT));
            if (result.isRedirect()) {
                if (++redirects > MAX_REDIRECTS) {
                    throw new WebSearchException(
                            "DuckDuckGo search refused: too many redirects (cap " + MAX_REDIRECTS + ").");
                }
                URI current = pinned.uri();
                URI target = resolveLocation(current, result.location().orElseThrow());
                if ("https".equalsIgnoreCase(current.getScheme())
                        && "http".equalsIgnoreCase(target.getScheme())) {
                    throw new WebSearchException(
                            "DuckDuckGo search refused an HTTPS→HTTP downgrade redirect to: " + target);
                }
                approved = guard.check(target);   // re-validate the target through the full policy (B1).
                continue;
            }
            if (result.status() != 200) {
                throw new WebSearchException(
                        "DuckDuckGo search returned HTTP " + result.status() + ".");
            }
            return result.body();
        }
    }

    /**
     * Resolve a possibly-relative redirect {@code Location} against the current hop, refusing a malformed
     * one with an actionable message (mirrors {@code WebFetchTool.resolveLocation} — a raw
     * {@code IllegalArgumentException} would reach the model as an unexplained internal error).
     */
    private static URI resolveLocation(URI current, String location) {
        try {
            return current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new WebSearchException(
                    "DuckDuckGo search got a malformed redirect Location: " + location);
        }
    }

    /** DuckDuckGo marks a genuine empty-result page with a {@code no-results} block; distinguish it from drift. */
    private static boolean isGenuineNoResults(String html) {
        return html != null && html.toLowerCase().contains("no-results");
    }
}
