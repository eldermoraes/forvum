package ai.forvum.tools.web;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.util.List;
import java.util.Optional;

/**
 * Runs a web search ({@code web.search}, scope {@link PermissionScope#WEB_SEARCH}; PR-6, pluggable backend
 * #192). READ-only outbound HTTP, so {@link #SPEC} uses the backward-compatible 4-arg {@link ToolSpec}
 * constructor ({@code userConfirmRequired = false}) — deliberately OUT of the P2-14/#39 approval gate. It
 * sits behind the engine's belt + P2-11 RBAC scope gates only.
 *
 * <p>The backend is a {@code tools/web.json} choice ({@link WebSearchBackend}): {@link DuckDuckGoBackend}
 * (the keyless default, so search works out of the box) or {@link BraveBackend} (the keyed option).
 * {@link #search} implements the precedence the issue binds:
 * <ol>
 *   <li>an explicit {@code "backend"} value selects that backend (unknown value → an actionable message);</li>
 *   <li>else a {@code braveApiKey} selects {@code brave} (existing Brave users keep working, zero edits);</li>
 *   <li>else {@code duckduckgo} (the keyless default).</li>
 * </ol>
 * A CONFIG-shaped problem (Brave selected but no key; an unknown backend value) RETURNS an actionable
 * message and makes NO network call; a backend's RUNTIME failure throws {@link WebSearchException} (audited
 * {@code error}, rendered back to the model). Every backend maps its results to a common
 * {@link SearchResult} list rendered here, so the model-facing block is byte-shape identical across backends.
 */
public final class WebSearchTool {

    /** The tool this class implements, contributed to the registry by {@code WebToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "web.search",
            "Search the web and return a list of result titles, URLs and snippets. "
          + "Use to discover pages; follow up with web.fetch to read one.",
            PermissionScope.WEB_SEARCH,
            "{\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"description\":\"the search query\"},"
          + "\"count\":{\"type\":\"integer\",\"description\":\"max results (1-20, default 5)\"}},"
          + "\"required\":[\"query\"]}");

    /** The maximum {@code count} the tool honors for a single search. */
    public static final int MAX_COUNT = 20;

    private final BraveSearchApi braveApi;
    private final HttpFetcher fetcher;

    public WebSearchTool(BraveSearchApi braveApi, HttpFetcher fetcher) {
        this.braveApi = braveApi;
        this.fetcher = fetcher;
    }

    /**
     * Search for {@code query}, returning at most {@code count} results (clamped to {@code [1, MAX_COUNT]})
     * as a text block. Selects the backend per {@code spec} (the precedence above); a config-shaped issue
     * returns an actionable message with no network call, and an empty result set returns {@code "no
     * results."}.
     */
    public String search(String query, int count, WebToolConfig.Spec spec) {
        int clamped = Math.max(1, Math.min(MAX_COUNT, count));
        String selected = selectedBackend(spec);
        Optional<String> braveKey = braveKey(spec);

        WebSearchBackend impl = switch (selected) {
            case "duckduckgo" -> new DuckDuckGoBackend(fetcher);
            case "brave" -> braveKey.map(key -> (WebSearchBackend) new BraveBackend(braveApi, key)).orElse(null);
            default -> null;   // config-shaped: an unknown backend value.
        };

        if (impl == null) {
            return notConfigured(selected);
        }
        return format(impl.search(query, clamped));
    }

    /**
     * The backend id {@code spec} resolves to under the precedence (explicit {@code backend} &gt;
     * {@code braveApiKey}-implies-brave &gt; keyless {@code duckduckgo} default), lower-cased. The single
     * source of truth for backend selection, shared by {@link #search} and {@link #configGap} so the tool's
     * runtime behavior and the {@code forvum tools}/{@code doctor} config-gap hint can never drift.
     */
    static String selectedBackend(WebToolConfig.Spec spec) {
        String backend = spec.searchBackend().map(String::strip).filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .orElse(null);
        return backend != null ? backend : (braveKey(spec).isPresent() ? "brave" : "duckduckgo");
    }

    /**
     * The actionable config-gap hint for {@code web.search} under {@code spec} (#184), or empty when the
     * resolved backend is ready to run: the keyless DuckDuckGo default has NO gap; Brave (selected or
     * key-implied) with no key, or an unknown backend value, name the file + field to fix. The returned
     * string is exactly the message {@link #search} would return for that config-shaped state — no network.
     */
    static Optional<String> configGap(WebToolConfig.Spec spec) {
        String selected = selectedBackend(spec);
        if ("duckduckgo".equals(selected)) {
            return Optional.empty();
        }
        if ("brave".equals(selected) && braveKey(spec).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(notConfigured(selected));
    }

    private static Optional<String> braveKey(WebToolConfig.Spec spec) {
        return spec.braveApiKey().map(String::strip).filter(k -> !k.isBlank());
    }

    /** The actionable message for a config-shaped problem (no network call was made). */
    private static String notConfigured(String selected) {
        if ("brave".equals(selected)) {
            return "web search backend 'brave' needs a braveApiKey in tools/web.json; the default backend "
                 + "'duckduckgo' needs no key (remove \"backend\" or set \"backend\": \"duckduckgo\").";
        }
        // An explicit but unrecognized backend value.
        return "web search backend '" + selected + "' is not recognized (valid: duckduckgo, brave). "
             + "Set a valid \"backend\" in tools/web.json, or remove it for the keyless default.";
    }

    /** Render normalized results to a compact numbered text block; skip a URL-less result. */
    static String format(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "no results.";
        }
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (SearchResult r : results) {
            if (r == null || r.url() == null || r.url().isBlank()) {
                continue;   // a result with no URL is not actionable; skip it.
            }
            if (n > 0) {
                out.append('\n');
            }
            n++;
            out.append(n).append(". ").append(orEmpty(r.title())).append('\n')
               .append("   ").append(r.url().strip());
            String snippet = orEmpty(r.snippet());
            if (!snippet.isBlank()) {
                out.append('\n').append("   ").append(snippet.strip());
            }
        }
        return n == 0 ? "no results." : out.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
