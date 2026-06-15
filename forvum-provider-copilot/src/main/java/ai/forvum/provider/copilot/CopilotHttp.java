package ai.forvum.provider.copilot;

import java.util.Map;

/**
 * The narrow HTTP seam the GitHub/Copilot OAuth flow runs over, so {@link CopilotAuth} stays free of any
 * concrete HTTP client and tests drive it with a scripted fake (no live GitHub). The production
 * implementation ({@link JdkCopilotHttp}) uses the JDK {@code java.net.http.HttpClient} (native-safe).
 */
public interface CopilotHttp {

    /** A minimal HTTP response: the status code and the raw body text. */
    record Resp(int status, String body) {
    }

    /** POST {@code form} as {@code application/x-www-form-urlencoded} with {@code headers}. */
    Resp postForm(String url, Map<String, String> form, Map<String, String> headers);

    /** GET {@code url} with {@code headers}. */
    Resp get(String url, Map<String, String> headers);
}
