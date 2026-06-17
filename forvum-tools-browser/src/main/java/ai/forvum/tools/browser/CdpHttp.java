package ai.forvum.tools.browser;

/**
 * The narrow HTTP seam the CDP discovery GETs run over, so {@link CdpDiscovery} stays free of any concrete
 * HTTP client and tests drive it with a scripted fake (no live Chrome). The production implementation
 * ({@link JdkCdpHttp}) uses the JDK {@code java.net.http.HttpClient} — the proven native-safe pure-JDK seam
 * (the same choice {@code forvum-provider-copilot}'s {@code JdkCopilotHttp} and the un-swapped model
 * providers use).
 */
public interface CdpHttp {

    /** A minimal HTTP response: the status code and the raw body text. */
    record Resp(int status, String body) {
    }

    /** GET {@code url}, returning the status and body, or throwing on a network/transport error. */
    Resp get(String url);
}
