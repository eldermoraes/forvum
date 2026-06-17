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
 * <p>The arbitrary URL is the module's only SSRF-exposed surface, so every fetch first passes
 * {@link EgressGuard} (block loopback/link-local/private by default). The fetch itself runs over the
 * {@link HttpFetcher} seam (production: pure-JDK {@code java.net.http}, native-safe); the decoded body is
 * truncated to {@code maxChars} so a large page cannot blow the model's context window.
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

    private final HttpFetcher fetcher;
    private final EgressGuard egressGuard;
    private final int maxChars;

    public WebFetchTool(HttpFetcher fetcher, EgressGuard egressGuard, int maxChars) {
        this.fetcher = fetcher;
        this.egressGuard = egressGuard;
        this.maxChars = maxChars;
    }

    /**
     * Fetch {@code url} and return the decoded text body, truncated to {@code maxChars}. Throws
     * {@link EgressDeniedException} (before any network call) if the URL is denied by the egress policy.
     */
    public String fetch(String url) {
        URI uri = egressGuard.check(url);
        HttpFetcher.FetchResult result = fetcher.get(uri);
        return truncate(result.body());
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
