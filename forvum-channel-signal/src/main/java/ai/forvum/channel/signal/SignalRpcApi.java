package ai.forvum.channel.signal;

import ai.forvum.channel.signal.dto.JsonRpcRequest;
import ai.forvum.channel.signal.dto.JsonRpcResponse;

import io.quarkus.rest.client.reactive.Url;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the signal-cli daemon's JSON-RPC endpoint (the outbound reply path; the
 * inbound path is the SSE event stream, hand-rolled in {@link SignalChannel}). It is a plain blocking
 * client whose method returns the typed response directly — NOT a Mutiny {@code Uni}/{@code Multi}
 * (reactive where a virtual thread suffices is a PR-reject, CLAUDE.md §3.8). The single caller,
 * {@link EnvelopeProcessor}, runs each call inside the stream-consuming worker on a virtual thread,
 * where the REST client blocks the virtual thread without pinning the carrier thread.
 *
 * <p>The daemon's base URL is OPERATOR config ({@code baseUrl} in {@code channels/signal.json}, e.g.
 * {@code http://localhost:8080}) read at runtime — so it cannot be a compile-time constant or a static
 * config value. Each call takes the resolved base URL as a per-invocation {@code @Url} override
 * ({@code io.quarkus.rest.client.reactive.Url}); the mandatory static
 * {@code quarkus.rest-client."signal-rpc".url} is a placeholder the {@code @Url} replaces. Unlike
 * Telegram/Discord there is no secret token — the daemon is a local, unauthenticated endpoint.
 */
@RegisterRestClient(configKey = "signal-rpc")
public interface SignalRpcApi {

    /**
     * POST one JSON-RPC 2.0 request to {@code {baseUrl}/api/v1/rpc}. The caller inspects the returned
     * envelope's {@code error}; a transport failure is surfaced by the thrown exception, logged
     * (redacted) by the caller.
     *
     * @param baseUrl per-invocation base URL of the operator-run daemon
     * @param request the JSON-RPC request (v0.5 sends only {@code method: "send"})
     */
    @POST
    @jakarta.ws.rs.Path("/api/v1/rpc")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonRpcResponse rpc(@Url String baseUrl, JsonRpcRequest request);
}
