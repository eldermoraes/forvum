package ai.forvum.provider.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GitHub Copilot authentication flow (#42), replicated from the OpenClaw reference
 * ({@code extensions/github-copilot/login.ts}, {@code src/agents/github-copilot-token.ts}):
 *
 * <ol>
 *   <li><b>Device-code login</b> — {@link #requestDeviceCode()} obtains a {@code user_code} +
 *       {@code verification_uri}; {@link #pollForAccessToken} polls until the user authorizes, yielding a
 *       long-lived <em>GitHub</em> token.</li>
 *   <li><b>Copilot-token exchange</b> — {@link #exchangeCopilotToken(String)} trades the GitHub token for a
 *       short-lived <em>Copilot</em> token (with an {@code expires_at}) and derives the OpenAI-compatible API
 *       base URL from the token's embedded {@code proxy-ep}.</li>
 * </ol>
 *
 * <p>All network IO goes through the {@link CopilotHttp} seam (a fake in tests). Sleeping + the clock are
 * injectable so the poll loop is deterministically testable. This class is langchain4j-free; the provider
 * turns its {@link CopilotToken} into an {@code OpenAiChatModel}.
 */
public class CopilotAuth {

    /** VS Code's GitHub-App client id (the public Copilot device-flow client; OpenClaw-confirmed). */
    static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    static final String DEFAULT_API_BASE_URL = "https://api.individual.githubcopilot.com";
    static final String SCOPE = "read:user";

    /** Static IDE headers GitHub Copilot expects (OpenClaw {@code copilot-dynamic-headers.ts}). */
    static final String EDITOR_VERSION = "vscode/1.96.2";
    static final String USER_AGENT = "GitHubCopilotChat/0.26.7";
    static final String GITHUB_API_VERSION = "2025-04-01";

    private static final Pattern PROXY_EP = Pattern.compile("(?:^|;)\\s*proxy-ep=([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A sleep seam so the poll loop can be driven with no real delay in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final CopilotHttp http;
    private final Sleeper sleeper;
    private final LongSupplier clock;

    public CopilotAuth(CopilotHttp http) {
        this(http, Thread::sleep, System::currentTimeMillis);
    }

    public CopilotAuth(CopilotHttp http, Sleeper sleeper, LongSupplier clock) {
        this.http = http;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The device-code grant a {@code copilot login} prints + polls on. */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             long expiresInSeconds, long intervalSeconds) {
    }

    /** A short-lived Copilot API token with its expiry (ms epoch) and the derived OpenAI-compatible base URL. */
    public record CopilotToken(String token, long expiresAtMs, String baseUrl) {
    }

    /** Step 1a: request a device code (GitHub returns the user code + verification URI to show the user). */
    public DeviceCode requestDeviceCode() {
        CopilotHttp.Resp res = http.postForm(DEVICE_CODE_URL,
                Map.of("client_id", CLIENT_ID, "scope", SCOPE),
                Map.of("Accept", "application/json", "Content-Type", "application/x-www-form-urlencoded"));
        if (res.status() < 200 || res.status() >= 300) {
            throw new CopilotAuthException("GitHub device code request failed: HTTP " + res.status());
        }
        JsonNode json = readTree(res.body(), "device code");
        String deviceCode = text(json, "device_code");
        String userCode = text(json, "user_code");
        String verificationUri = text(json, "verification_uri");
        if (deviceCode == null || userCode == null || verificationUri == null) {
            throw new CopilotAuthException("GitHub device code response missing required fields");
        }
        return new DeviceCode(deviceCode, userCode, verificationUri,
                json.path("expires_in").asLong(900), json.path("interval").asLong(5));
    }

    /**
     * Step 1b: poll until the user authorizes, returning the long-lived GitHub token. Honors
     * {@code authorization_pending} (wait the interval), {@code slow_down} (wait interval + 2s), and the
     * terminal {@code expired_token}/{@code access_denied} errors. Throws on overall expiry.
     */
    public String pollForAccessToken(String deviceCode, long intervalMs, long expiresAtMs) {
        // Floor the interval (mirrors the OpenClaw reference Math.max(1000, ...)): a malformed device-code
        // response with interval 0 would otherwise busy-poll GitHub, and a negative value would make
        // Thread.sleep throw an uncaught IllegalArgumentException. Self-protecting for every caller.
        long pollInterval = Math.max(1000L, intervalMs);
        Map<String, String> form = Map.of("client_id", CLIENT_ID, "device_code", deviceCode,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        Map<String, String> headers = Map.of("Accept", "application/json",
                "Content-Type", "application/x-www-form-urlencoded");
        while (clock.getAsLong() < expiresAtMs) {
            CopilotHttp.Resp res = http.postForm(ACCESS_TOKEN_URL, form, headers);
            if (res.status() < 200 || res.status() >= 300) {
                throw new CopilotAuthException("GitHub device token request failed: HTTP " + res.status());
            }
            JsonNode json = readTree(res.body(), "device token");
            String accessToken = text(json, "access_token");
            if (accessToken != null) {
                return accessToken;
            }
            switch (text(json, "error") == null ? "unknown" : text(json, "error")) {
                case "authorization_pending" -> sleepQuietly(pollInterval);
                case "slow_down" -> sleepQuietly(pollInterval + 2000);
                case "expired_token" -> throw new CopilotAuthException(
                        "GitHub device code expired; run `forvum copilot login` again");
                case "access_denied" -> throw new CopilotAuthException("GitHub login was cancelled");
                default -> throw new CopilotAuthException(
                        "GitHub device flow error: " + text(json, "error"));
            }
        }
        throw new CopilotAuthException("GitHub device code expired; run `forvum copilot login` again");
    }

    /** Step 2: exchange the GitHub token for a short-lived Copilot token + derive the API base URL. */
    public CopilotToken exchangeCopilotToken(String githubToken) {
        Map<String, String> headers = new LinkedHashMap<>(ideHeaders(true));
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + githubToken);
        CopilotHttp.Resp res = http.get(COPILOT_TOKEN_URL, headers);
        if (res.status() < 200 || res.status() >= 300) {
            throw new CopilotAuthException("Copilot token exchange failed: HTTP " + res.status());
        }
        JsonNode json = readTree(res.body(), "Copilot token");
        String token = text(json, "token");
        if (token == null || token.isBlank()) {
            throw new CopilotAuthException("Copilot token response missing token");
        }
        long expiresAtMs = parseExpiresAtMs(json.get("expires_at"));
        return new CopilotToken(token, expiresAtMs, deriveApiBaseUrl(token));
    }

    /** The static IDE headers GitHub Copilot expects; {@code apiVersion} adds {@code X-Github-Api-Version}. */
    public static Map<String, String> ideHeaders(boolean apiVersion) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Editor-Version", EDITOR_VERSION);
        h.put("User-Agent", USER_AGENT);
        if (apiVersion) {
            h.put("X-Github-Api-Version", GITHUB_API_VERSION);
        }
        return h;
    }

    /**
     * Derive the OpenAI-compatible API base URL from the Copilot token: it is a {@code ;}-delimited kv set
     * carrying {@code proxy-ep=<host>}; the host's {@code proxy.} prefix maps to {@code api.}. Falls back to
     * {@link #DEFAULT_API_BASE_URL} when no usable {@code proxy-ep} is present.
     */
    public static String deriveApiBaseUrl(String copilotToken) {
        if (copilotToken == null) {
            return DEFAULT_API_BASE_URL;
        }
        Matcher m = PROXY_EP.matcher(copilotToken);
        if (!m.find()) {
            return DEFAULT_API_BASE_URL;
        }
        String proxyEp = m.group(1).trim();
        if (proxyEp.isEmpty()) {
            return DEFAULT_API_BASE_URL;
        }
        String withScheme = proxyEp.matches("(?i)^https?://.*") ? proxyEp : "https://" + proxyEp;
        try {
            java.net.URI uri = java.net.URI.create(withScheme);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return DEFAULT_API_BASE_URL;
            }
            return "https://" + host.replaceFirst("(?i)^proxy\\.", "api.");
        } catch (RuntimeException e) {
            return DEFAULT_API_BASE_URL;
        }
    }

    /** GitHub returns {@code expires_at} as unix seconds (sometimes ms); the 1e11 threshold disambiguates. */
    static long parseExpiresAtMs(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new CopilotAuthException("Copilot token response missing expires_at");
        }
        long value;
        if (node.isNumber()) {
            value = node.asLong();
        } else if (node.isTextual() && !node.asText().isBlank()) {
            try {
                value = Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new CopilotAuthException("Copilot token response has invalid expires_at");
            }
        } else {
            throw new CopilotAuthException("Copilot token response missing expires_at");
        }
        return value < 100_000_000_000L ? value * 1000L : value;
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CopilotAuthException("Interrupted while waiting for GitHub authorization");
        }
    }

    private static JsonNode readTree(String body, String what) {
        try {
            return MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new CopilotAuthException("Unexpected " + what + " response from GitHub");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }
}
