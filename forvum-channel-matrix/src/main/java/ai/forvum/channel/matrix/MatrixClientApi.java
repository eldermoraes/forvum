package ai.forvum.channel.matrix;

import ai.forvum.channel.matrix.dto.JoinRequest;
import ai.forvum.channel.matrix.dto.SendMessageRequest;
import ai.forvum.channel.matrix.dto.SyncResponse;

import io.quarkus.rest.client.reactive.Url;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Matrix client-server API v3 (ULTRAPLAN §5.5 / Risk #12). It is a plain
 * blocking client whose methods return typed values directly — NOT a Mutiny {@code Uni}/{@code Multi}
 * return type (reactive where a virtual thread suffices is a PR-reject, CLAUDE.md §3.8). The callers run
 * each call inside the sync worker on a virtual thread, where the REST client blocks the virtual thread
 * without pinning the carrier thread.
 *
 * <p>The homeserver base URL is operator config read from {@code channels/matrix.json} at runtime — so
 * it cannot be a compile-time constant or a static config value. Each method takes the resolved base URL
 * as a per-invocation {@code @Url} override ({@code io.quarkus.rest.client.reactive.Url}); the mandatory
 * static {@code quarkus.rest-client."matrix-client-api".url} is a placeholder the {@code @Url} replaces.
 * The access token is a per-deployment secret passed as the {@code Authorization: Bearer <token>}
 * <em>header</em> per invocation (a {@link HeaderParam}) — NEVER an {@code access_token} query param, so
 * it never lands in a request-URL log line. {@code read-timeout} must exceed the long-poll
 * {@code timeout} (configured in {@code META-INF/microprofile-config.properties}) so the long poll is
 * never cut short by the client.
 */
@RegisterRestClient(configKey = "matrix-client-api")
public interface MatrixClientApi {

    /**
     * Long-poll for new events ({@code GET /_matrix/client/v3/sync}). {@code since} acknowledges the
     * previous batch and requests only newer events — {@code null} on the FIRST call (the initial
     * snapshot, omitted from the query string); {@code timeout} (milliseconds) holds the connection open
     * until an event arrives or the timeout elapses.
     *
     * @param baseUrl       per-invocation homeserver base URL
     * @param authorization the {@code Bearer <accessToken>} authorization header value (the secret)
     */
    @GET
    @jakarta.ws.rs.Path("/_matrix/client/v3/sync")
    SyncResponse sync(@Url String baseUrl,
                      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                      @QueryParam("since") String since,
                      @QueryParam("timeout") int timeout);

    /**
     * Send a text message to {@code roomId} ({@code PUT /_matrix/client/v3/rooms/{roomId}/send/
     * m.room.message/{txnId}}). {@code txnId} MUST be unique per send for this access token — the
     * homeserver dedupes on it ({@link TransactionIds}). The response envelope ({@code event_id}) is
     * ignored — a failed send is surfaced by the thrown exception, logged (redacted) by the caller.
     *
     * @param baseUrl       per-invocation homeserver base URL
     * @param authorization the {@code Bearer <accessToken>} authorization header value (the secret)
     */
    @PUT
    @jakarta.ws.rs.Path("/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void sendMessage(@Url String baseUrl,
                     @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                     @PathParam("roomId") String roomId,
                     @PathParam("txnId") String txnId,
                     SendMessageRequest body);

    /**
     * Join a room the bot was invited to ({@code POST /_matrix/client/v3/join/{roomId}}). The body is
     * the explicit empty JSON object ({@link JoinRequest}); the response envelope is ignored.
     *
     * @param baseUrl       per-invocation homeserver base URL
     * @param authorization the {@code Bearer <accessToken>} authorization header value (the secret)
     */
    @POST
    @jakarta.ws.rs.Path("/_matrix/client/v3/join/{roomId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void join(@Url String baseUrl,
              @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
              @PathParam("roomId") String roomId,
              JoinRequest body);
}
