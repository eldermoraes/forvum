package ai.forvum.tools.browser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The production {@link CdpHttp}: the JDK {@code java.net.http.HttpClient} (native-safe, no extra HTTP
 * stack — the same pure-JDK choice {@code JdkCopilotHttp} and the un-swapped model providers use). A single
 * client is reused. Connect timeout is the {@code BrowserConfig} connect timeout.
 */
public final class JdkCdpHttp implements CdpHttp {

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkCdpHttp(int connectTimeoutMs) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.requestTimeout = Duration.ofMillis(connectTimeoutMs);
    }

    @Override
    public Resp get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Resp(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new CdpException(
                    "Cannot reach Chrome's remote-debugging endpoint at " + url + " (" + e.getMessage()
                  + "). Start Chrome with --remote-debugging-port=9222.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted while reaching Chrome's remote-debugging endpoint.", e);
        }
    }
}
