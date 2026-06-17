package ai.forvum.tools.web;

import java.net.URI;

/**
 * The narrow HTTP seam {@code web.fetch} runs over, so {@link WebFetchTool} stays free of any concrete
 * HTTP client and tests drive it with a fake (no network). The production implementation
 * ({@link JdkHttpFetcher}) uses the JDK {@code java.net.http.HttpClient} (native-safe, the same pure-JDK
 * choice {@code forvum-provider-copilot}'s {@code JdkCopilotHttp} and the engine make).
 */
public interface HttpFetcher {

    /**
     * GET {@code uri} and return the response. The implementation follows a bounded number of redirects,
     * sends a User-Agent, caps the response size, and decodes the body using the response charset. The
     * {@code uri} has already passed {@link EgressGuard}.
     *
     * @throws java.io.UncheckedIOException on a network/IO failure (no key/URL detail beyond the host)
     */
    FetchResult get(URI uri);

    /** A fetched web resource: the HTTP status, the resolved content type, and the decoded text body. */
    record FetchResult(int status, String contentType, String body) {
    }
}
