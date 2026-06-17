package ai.forvum.tools.web;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Web tool module (PR-6, resolving the ULTRAPLAN §epic-4 web-tool surface). Contributes {@code web.fetch}
 * (arbitrary-URL fetch over {@code java.net.http}, scope {@code WEB_FETCH}) and {@code web.search} (Brave
 * Search over a blocking REST client, scope {@code WEB_SEARCH}) to the engine's global ToolRegistry, which
 * discovers this {@code @ApplicationScoped} bean via CDI and (M18 Option A) executes them through
 * {@link #invoke(String, Map)}. Both tools are READ-only outbound HTTP, so neither declares
 * {@code userConfirmRequired} — they sit behind the engine's belt + P2-11 RBAC scope gates only, NOT the
 * #39 user-approval gate.
 *
 * <p>This provider only dispatches the permitted call by name to the tool logic (no reflection), mirroring
 * {@code FilesystemToolProvider}. The {@link EgressGuard} (web.fetch's SSRF confinement) and the Brave key
 * (web.search) are read from the live {@code tools/web.json} spec per invocation, so an operator's edit
 * takes effect without a restart and the module is INERT with no {@code ~/.forvum/}.
 */
@ForvumExtension
@ApplicationScoped
public class WebToolProvider extends AbstractToolProvider {

    /** Char cap on a fetched body so a large page cannot blow the model context window. */
    static final int FETCH_MAX_CHARS = 100_000;

    @Inject
    HttpFetcher fetcher;

    @Inject
    @RestClient
    BraveSearchApi braveApi;

    @Inject
    WebToolConfig config;

    @Override
    public String extensionId() {
        return "web";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(WebFetchTool.SPEC, WebSearchTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        WebToolConfig.Spec spec = config.read();
        return switch (toolName) {
            case "web.fetch" -> new WebFetchTool(fetcher,
                    new EgressGuard(spec.allowPrivateNetwork(), spec.allowedPorts()),
                    FETCH_MAX_CHARS).fetch(stringArg(arguments, "url"));
            case "web.search" -> new WebSearchTool(braveApi)
                    .search(stringArg(arguments, "query"), intArg(arguments, "count", 5), spec.braveApiKey());
            default -> throw new IllegalArgumentException(
                    "WebToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides web.fetch, web.search.");
        };
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a web tool call.");
        }
        return value.toString();
    }

    /** An optional integer argument {@code key}, defaulting to {@code fallback} when absent or unparsable. */
    private static int intArg(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
