package ai.forvum.tools.web;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import java.net.URI;

/**
 * Fetches the text content of an arbitrary, model-supplied URL ({@code web.fetch}, scope
 * {@link PermissionScope#WEB_FETCH}; PR-6, resolving the ULTRAPLAN §epic-4 web-tool surface). READ-only
 * outbound HTTP, so the {@link #SPEC} uses the backward-compatible 4-arg {@link ToolSpec} constructor
 * ({@code userConfirmRequired = false}) — it is deliberately OUT of the P2-14/#39 user-approval gate
 * (which is for destructive tools such as {@code shell.exec}). It sits behind the engine's two existing
 * gates: belt membership and the P2-11 RBAC effective-scopes check.
 *
 * <p>The arbitrary URL is the module's only SSRF-exposed surface, so this class owns ALL egress policy:
 * the {@link HttpFetcher} is a dumb single-hop transport (no redirect follow), and {@code web.fetch}
 * itself runs a bounded redirect loop ({@link #MAX_REDIRECTS} hops) re-running {@link EgressGuard#check}
 * on EVERY hop's absolute {@code Location} (B1), re-resolving + re-validating each hop immediately before
 * the send ({@link EgressGuard#recheck}, E-ii), and refusing an HTTPS→HTTP downgrade redirect (the refusal
 * {@code Redirect.NORMAL} previously gave for free). The decoded body is truncated to {@code maxChars} so
 * a large page cannot blow the model's context window.
 */
public final class WebFetchTool {

    /** The tool this class implements, contributed to the registry by {@code WebToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "web.fetch",
            "Fetch the text content of a public web URL (http/https) and return it (truncated if large). "
          + "Use for reading a known page; internal/private addresses are refused.",
            PermissionScope.WEB_FETCH,
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\","
          + "\"description\":\"the absolute http(s) URL to fetch\"}},\"required\":[\"url\"]}");

    /** The hard cap on redirect hops {@code web.fetch} follows before giving up (defense against loops). */
    static final int MAX_REDIRECTS = 5;

    private final HttpFetcher fetcher;
    private final EgressGuard egressGuard;
    private final int maxChars;

    public WebFetchTool(HttpFetcher fetcher, EgressGuard egressGuard, int maxChars) {
        this.fetcher = fetcher;
        this.egressGuard = egressGuard;
        this.maxChars = maxChars;
    }

    /**
     * Fetch {@code url} and return the decoded text body, truncated to {@code maxChars}, following up to
     * {@link #MAX_REDIRECTS} redirects with the egress guard re-run on every hop. Throws
     * {@link EgressDeniedException} (before that hop's network call) if any hop's URL is denied by the
     * egress policy, or if the redirect cap is exceeded.
     */
    public String fetch(String url) {
        EgressGuard.Approved approved = egressGuard.check(url);
        int redirects = 0;
        while (true) {
            // E-ii: re-resolve + re-validate immediately before the actual send to shrink the rebind
            // window (a name that has flipped to an internal address since check() is denied here).
            EgressGuard.Approved pinned = egressGuard.recheck(approved.uri());

            HttpFetcher.FetchResult result = fetcher.get(pinned);
            if (!result.isRedirect()) {
                return truncate(result.body());
            }
            if (++redirects > MAX_REDIRECTS) {
                throw new EgressDeniedException(
                        "web.fetch refused: too many redirects (cap " + MAX_REDIRECTS + ").");
            }
            URI current = pinned.uri();
            URI target = resolveLocation(current, result.location().orElseThrow());
            // Preserve the HTTPS→HTTP downgrade refusal Redirect.NORMAL gave for free.
            if ("https".equalsIgnoreCase(current.getScheme())
                    && "http".equalsIgnoreCase(target.getScheme())) {
                throw new EgressDeniedException(
                        "web.fetch refused an HTTPS→HTTP downgrade redirect to: " + target);
            }
            // Re-validate the redirect target through the FULL policy before following it (B1).
            approved = egressGuard.check(target);
        }
    }

    /** Resolve a possibly-relative {@code Location} against the current hop's URI; deny a malformed one. */
    private static URI resolveLocation(URI current, String location) {
        try {
            return current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new EgressDeniedException("web.fetch got a malformed redirect Location: " + location);
        }
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= maxChars) {
            return body;
        }
        return body.substring(0, maxChars)
             + "\n\n[... truncated: response exceeded " + maxChars + " characters]";
    }
}
