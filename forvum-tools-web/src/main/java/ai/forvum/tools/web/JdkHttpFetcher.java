package ai.forvum.tools.web;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The production {@link HttpFetcher}: the JDK {@code java.net.http.HttpClient} (native-safe, no extra HTTP
 * stack — the same pure-JDK choice {@code forvum-provider-copilot}'s {@code JdkCopilotHttp} and the engine
 * make; the [M20/Risk#5] empty-native-ServiceLoader trap is langchain4j-specific and does NOT touch
 * {@code java.net.http}). It follows redirects (NORMAL, which refuses HTTPS→HTTP downgrades), sends a
 * User-Agent, reads the body as raw bytes, decodes with the response charset, and caps the read so a huge
 * page cannot exhaust memory.
 *
 * <p>{@code @ApplicationScoped}: the {@link HttpClient} is a non-static field built lazily on first use
 * (never at {@code @Startup}), so the bean adds zero cold-start cost and stays inert with no turn. No
 * synchronized; the blocking {@code send} runs on the turn's virtual thread.
 */
@ApplicationScoped
public class JdkHttpFetcher implements HttpFetcher {

    private static final String USER_AGENT = "Forvum/0.1 (+https://forvum.ai)";
    private static final int MAX_BYTES = 2 * 1024 * 1024;   // 2 MiB hard cap on the fetched body.

    private volatile HttpClient client;

    @Override
    public FetchResult get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (bytes == null) {
                bytes = new byte[0];
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            Charset charset = charsetOf(contentType);
            int len = Math.min(bytes.length, MAX_BYTES);
            String body = new String(bytes, 0, len, charset);
            return new FetchResult(response.statusCode(), contentType, body);
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

    /** Lazily build the client on first use, then reuse it. NORMAL redirect refuses HTTPS→HTTP downgrade. */
    private HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            local = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
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
