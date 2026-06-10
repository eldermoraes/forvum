package ai.forvum.channel.slack;

import ai.forvum.channel.slack.dto.ChatPostMessage;
import ai.forvum.channel.slack.dto.ChatPostMessageResponse;
import ai.forvum.channel.slack.dto.ConnectionsOpenResponse;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Slack Web API (the outbound/bootstrap path; the inbound path is the
 * Socket Mode WebSocket). It is a plain blocking client whose methods return typed values directly —
 * NOT a Mutiny {@code Uni}/{@code Multi} (reactive where a virtual thread suffices is a PR-reject,
 * CLAUDE.md §3.8). Every caller runs on a virtual thread (the channel's connect worker, an inbound-frame
 * {@code @RunOnVirtualThread} handler), where the REST client blocks without pinning a carrier thread.
 *
 * <p>Like Discord (and unlike Telegram, which embeds its token in the URL path), the Slack tokens are
 * per-deployment secrets passed as the {@code Authorization: Bearer <token>} <em>header</em> per
 * invocation (a {@link HeaderParam}) — read from {@code channels/slack.json} at runtime, never a
 * compile-time constant. {@code apps.connections.open} takes the app-level {@code xapp-} token;
 * {@code chat.postMessage} takes the bot {@code xoxb-} token. The static base URL
 * {@code quarkus.rest-client."slack-api".url} is a fixed, non-secret value ({@code
 * https://slack.com/api}, set in {@code microprofile-config.properties}); only the tokens vary, and they
 * travel in the header (so they never land in a request-URL log line).
 */
@RegisterRestClient(configKey = "slack-api")
public interface SlackRestClient {

    /**
     * Mint a TEMPORARY Socket Mode WebSocket URL ({@code POST /apps.connections.open}, empty body).
     * Called before EVERY connect — the returned URL is single-use and never reused across reconnects.
     * Slack signals failure as {@code ok: false} with an {@code error} string (HTTP 200), so the caller
     * must check {@link ConnectionsOpenResponse#ok()}.
     *
     * @param authorization the {@code Bearer <xapp- app token>} authorization header value
     */
    @POST
    @Path("/apps.connections.open")
    ConnectionsOpenResponse connectionsOpen(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

    /**
     * Post a message to a Slack conversation ({@code POST /chat.postMessage}). Slack signals failure as
     * {@code ok: false} with an {@code error} string (HTTP 200), logged (redacted) by the caller — a
     * failed send must never take the socket loop down.
     *
     * @param authorization the {@code Bearer <xoxb- bot token>} authorization header value
     * @param body          the {@code { "channel": ..., "text": ... }} request body
     */
    @POST
    @Path("/chat.postMessage")
    @Consumes(MediaType.APPLICATION_JSON)
    ChatPostMessageResponse postMessage(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                        ChatPostMessage body);
}
