package ai.forvum.provider.memory.qdrant;

import ai.forvum.provider.memory.qdrant.dto.QdrantScrollRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollResponse;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchResponse;

import io.quarkus.rest.client.reactive.Url;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Qdrant points API (mirrors {@code TelegramBotApi}, ULTRAPLAN §5.5 / Risk
 * #12). It is a plain blocking client whose methods return typed values directly — NOT a Mutiny
 * {@code Uni}/{@code Multi} return type (reactive where a virtual thread suffices is a PR-reject,
 * CLAUDE.md §3.8). The single caller, {@link QdrantMemoryProvider}, runs each call inside
 * {@code retrieve} on a virtual thread, where the REST client blocks the virtual thread without pinning
 * the carrier thread.
 *
 * <p>The Qdrant base URL ({@code http://localhost:6333} for a local instance, or a Cloud cluster URL) is
 * a per-deployment value read from {@code memory/qdrant.json} at runtime, so it is supplied as a
 * per-invocation {@code @Url} override; the mandatory static
 * {@code quarkus.rest-client."qdrant-api".url} is a placeholder the {@code @Url} replaces. The optional
 * Qdrant API key is passed as the {@code api-key} header per call (blank when unset, which a local
 * unsecured Qdrant ignores).
 */
@RegisterRestClient(configKey = "qdrant-api")
public interface QdrantApi {

    /**
     * Vector search: {@code POST /collections/{collection}/points/search}. Returns the closest points,
     * scored, payload included.
     *
     * @param baseUrl    per-invocation Qdrant base URL
     * @param apiKey     the {@code api-key} header (blank when unset)
     * @param collection the collection to search
     * @param request    the search body (vector, limit, score_threshold, filter)
     */
    @POST
    @Path("/collections/{collection}/points/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    QdrantSearchResponse search(@Url String baseUrl,
                                @HeaderParam("api-key") String apiKey,
                                @PathParam("collection") String collection,
                                QdrantSearchRequest request);

    /**
     * Scroll (the embedding-free METADATA path): {@code POST /collections/{collection}/points/scroll}.
     * Returns a payload-filtered page of points, unscored.
     *
     * @param baseUrl    per-invocation Qdrant base URL
     * @param apiKey     the {@code api-key} header (blank when unset)
     * @param collection the collection to scroll
     * @param request    the scroll body (limit, filter)
     */
    @POST
    @Path("/collections/{collection}/points/scroll")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    QdrantScrollResponse scroll(@Url String baseUrl,
                                @HeaderParam("api-key") String apiKey,
                                @PathParam("collection") String collection,
                                QdrantScrollRequest request);
}
