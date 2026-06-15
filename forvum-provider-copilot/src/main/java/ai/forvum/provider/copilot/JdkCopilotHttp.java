package ai.forvum.provider.copilot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The production {@link CopilotHttp}: the JDK {@code java.net.http.HttpClient} (native-safe, no extra HTTP
 * stack — the same pure-JDK choice the un-swapped model providers use). A single client is reused.
 */
public final class JdkCopilotHttp implements CopilotHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(enc(k) + "=" + enc(v)));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(req::header);
        return send(req);
    }

    @Override
    public Resp get(String url, Map<String, String> headers) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(req::header);
        return send(req);
    }

    private Resp send(HttpRequest.Builder req) {
        try {
            HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(res.statusCode(), res.body());
        } catch (java.io.IOException e) {
            throw new CopilotAuthException("Network error talking to GitHub: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CopilotAuthException("Interrupted talking to GitHub", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
