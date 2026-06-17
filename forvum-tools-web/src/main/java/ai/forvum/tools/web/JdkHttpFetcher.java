package ai.forvum.tools.web;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The production {@link HttpFetcher}: the JDK {@code java.net.http.HttpClient} (native-safe, no extra HTTP
 * stack — the same pure-JDK choice {@code forvum-provider-copilot}'s {@code JdkCopilotHttp} and the engine
 * make; the [M20/Risk#5] empty-native-ServiceLoader trap is langchain4j-specific and does NOT touch
 * {@code java.net.http}).
 *
 * <p>It is a <strong>single-hop</strong> transport: the client is built with {@code Redirect.NEVER} so a
 * 3xx is returned to {@link WebFetchTool}, which re-runs the {@link EgressGuard} on the {@code Location}
 * before following it (B1 — no redirect can reach an internal target unchecked). For an HTTP target with a
 * guard-pinned address, it connects to that validated literal IP with an explicit {@code Host:} header
 * (closing the DNS-rebind window for HTTP, B2); for HTTPS it leaves the URI untouched (IP-rewriting would
 * break SNI/cert verification — the JDK client has no SNI override; the residual HTTPS rebind window is
 * narrowed by {@link EgressGuard#recheck} and documented there).
 *
 * <p>{@code @ApplicationScoped}: the {@link HttpClient} is a non-static field built lazily on first use
 * (never at {@code @Startup}), so the bean adds zero cold-start cost and stays inert with no turn. No
 * synchronized; the blocking {@code send} runs on the turn's virtual thread.
 */
@ApplicationScoped
public class JdkHttpFetcher implements HttpFetcher {

    static {
        // Allow setting the restricted "Host" header on the request so the HTTP IP-pin (B2) can connect to
        // a validated literal IP while presenting the original virtual host. Must be set before the
        // HttpClient is first initialized; set-only-if-absent leaves an operator override intact.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
    }

    private static final String USER_AGENT = "Forvum/0.1 (+https://forvum.ai)";
    private static final int MAX_BYTES = 2 * 1024 * 1024;   // 2 MiB hard cap on the fetched body.

    private volatile HttpClient client;

    @Override
    public FetchResult get(EgressGuard.Approved approved) {
        URI uri = approved.uri();
        InetAddress pin = approved.pinnedAddress();
        boolean http = "http".equalsIgnoreCase(uri.getScheme());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                .GET();

        if (http && pin != null) {
            // B2: connect to the validated literal IP, present the original host as the Host header.
            builder.uri(rewriteToIp(uri, pin)).header("Host", hostHeader(uri));
        } else {
            builder.uri(uri);
        }

        try {
            HttpResponse<byte[]> response =
                    client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (bytes == null) {
                bytes = new byte[0];
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            Charset charset = charsetOf(contentType);
            int len = Math.min(bytes.length, MAX_BYTES);
            String body = new String(bytes, 0, len, charset);
            Optional<String> location = response.headers().firstValue("location");
            return new FetchResult(response.statusCode(), contentType, body, location);
        } catch (java.io.IOException e) {
            // Redacted: only the host and the message, never headers/keys (web.fetch carries no secret,
            // but keep the discipline uniform with the rest-client redaction).
            throw new UncheckedIOException(
                    "web.fetch failed for host '" + uri.getHost() + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(
                    new java.io.InterruptedIOException("web.fetch interrupted for host '" + uri.getHost() + "'"));
        }
    }

    /** Rewrite the URI authority to the validated literal IP, preserving the port/path/query (HTTP only). */
    private static URI rewriteToIp(URI uri, InetAddress pin) {
        String ip = pin.getHostAddress();
        String authority = (pin instanceof Inet6Address) ? "[" + ip + "]" : ip;
        if (uri.getPort() != -1) {
            authority = authority + ":" + uri.getPort();
        }
        try {
            // Use the multi-arg constructor with the raw query so a query string is not double-escaped.
            return new URI(uri.getScheme(), authority, uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(
                    new java.io.IOException("web.fetch could not pin host '" + uri.getHost() + "' to its IP."));
        }
    }

    /** The {@code Host} header value: the original host, with the port when non-default. */
    private static String hostHeader(URI uri) {
        String host = uri.getHost();
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    /** Lazily build the client on first use, then reuse it. NEVER follows redirects (B1, single-hop). */
    private HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            local = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            client = local;
        }
        return local;
    }

    /** Best-effort charset from a {@code Content-Type} header; defaults to UTF-8. */
    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int idx = contentType.toLowerCase().indexOf("charset=");
            if (idx >= 0) {
                String raw = contentType.substring(idx + "charset=".length()).trim();
                int semi = raw.indexOf(';');
                if (semi >= 0) {
                    raw = raw.substring(0, semi).trim();
                }
                raw = raw.replace("\"", "").replace("'", "").trim();
                try {
                    if (!raw.isBlank() && Charset.isSupported(raw)) {
                        return Charset.forName(raw);
                    }
                } catch (RuntimeException ignored) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
