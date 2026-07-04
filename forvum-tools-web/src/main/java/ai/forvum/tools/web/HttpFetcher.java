package ai.forvum.tools.web;

import java.util.Map;
import java.util.Optional;

/**
 * The narrow HTTP seam {@code web.fetch} runs over, so {@link WebFetchTool} stays free of any concrete
 * HTTP client and tests drive it with a fake (no network). The production implementation
 * ({@link JdkHttpFetcher}) uses the JDK {@code java.net.http.HttpClient} (native-safe, the same pure-JDK
 * choice {@code forvum-provider-copilot}'s {@code JdkCopilotHttp} and the engine make).
 *
 * <p>This is a <strong>single-hop</strong> transport: it does NOT follow redirects. All egress policy —
 * the bounded redirect loop, the per-hop {@link EgressGuard} re-validation, and the HTTPS→HTTP downgrade
 * refusal — lives in {@link WebFetchTool}, so the connected address is always one the guard approved (B1).
 * For an HTTP {@link EgressGuard.Approved} carrying a pinned address, the implementation connects to that
 * validated literal IP with an explicit {@code Host:} header (closing the DNS-rebind window for HTTP, B2).
 */
public interface HttpFetcher {

    /**
     * Perform ONE GET to {@code approved.uri()} (no redirect follow), sending a User-Agent, capping the
     * response size, and decoding the body using the response charset. The URI has already passed
     * {@link EgressGuard#check}; if {@code approved.pinnedAddress()} is set and the scheme is HTTP, the
     * implementation connects to that literal IP with a {@code Host:} header.
     *
     * @throws java.io.UncheckedIOException on a network/IO failure (no key/URL detail beyond the host)
     */
    FetchResult get(EgressGuard.Approved approved);

    /**
     * As {@link #get(EgressGuard.Approved)} but overriding/adding request headers ({@code web.search}'s DDG
     * backend passes a browser-like {@code User-Agent}, #192). A header here REPLACES the implementation's
     * default of the same name (e.g. the {@code User-Agent}); the default keeps the honest Forvum UA for
     * {@code web.fetch}. Defaults to {@link #get(EgressGuard.Approved)} so the existing test fakes — which
     * implement only the 1-arg method — compile unchanged ([#166] default-method recipe); the production
     * {@link JdkHttpFetcher} overrides it.
     *
     * @throws java.io.UncheckedIOException on a network/IO failure (no key/URL detail beyond the host)
     */
    default FetchResult get(EgressGuard.Approved approved, Map<String, String> headers) {
        return get(approved);
    }

    /**
     * A single-hop HTTP response: the status, the resolved content type, the decoded text body, and the
     * {@code Location} header when present (so {@link WebFetchTool} can follow a redirect, re-guarding it).
     */
    record FetchResult(int status, String contentType, String body, Optional<String> location) {

        /** Whether this response is an HTTP redirect (3xx) that carries a {@code Location}. */
        boolean isRedirect() {
            return status >= 300 && status < 400 && location.isPresent();
        }
    }
}
