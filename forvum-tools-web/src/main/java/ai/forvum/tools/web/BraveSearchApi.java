package ai.forvum.tools.web;

import ai.forvum.tools.web.dto.BraveSearchResponse;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Brave Search web-search API (mirrors {@code QdrantApi} /
 * {@code TelegramBotApi}, the native-clean blocking-REST recipe, [P2-5]). It is a plain blocking client
 * whose method returns a typed value directly — NOT a Mutiny {@code Uni}/{@code Multi} (reactive where a
 * virtual thread suffices is a PR-reject, CLAUDE.md §3.8). The single caller, {@link WebSearchTool}, runs
 * each call inside {@code web.search} on the turn's virtual thread, where the REST client blocks the
 * virtual thread without pinning the carrier thread.
 *
 * <p>The base URL ({@code https://api.search.brave.com}) is the fixed, public host configured statically
 * in {@code META-INF/microprofile-config.properties}. The Brave subscription token is OPERATOR config,
 * passed as the {@code X-Subscription-Token} header per invocation (NOT a URL segment, so unlike Telegram
 * it never leaks in a URL); a REST-client exception is still redacted before logging.
 */
@RegisterRestClient(configKey = "brave-search")
public interface BraveSearchApi {

    /**
     * Web search: {@code GET /res/v1/web/search?q={query}&count={count}}.
     *
     * @param apiKey the {@code X-Subscription-Token} header value (the Brave subscription token)
     * @param query  the search query
     * @param count  the maximum number of results to return
     */
    @GET
    @Path("/res/v1/web/search")
    @Produces(MediaType.APPLICATION_JSON)
    BraveSearchResponse search(@HeaderParam("X-Subscription-Token") String apiKey,
                               @QueryParam("q") String query,
                               @QueryParam("count") int count);
}
