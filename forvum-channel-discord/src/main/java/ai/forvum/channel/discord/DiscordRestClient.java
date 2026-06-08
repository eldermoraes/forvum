package ai.forvum.channel.discord;

import ai.forvum.channel.discord.dto.CreateMessage;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Discord REST API (the outbound reply path; the inbound path is the
 * gateway WebSocket). It is a plain blocking client whose method returns {@code void} directly — NOT a
 * Mutiny {@code Uni}/{@code Multi} (reactive where a virtual thread suffices is a PR-reject, CLAUDE.md
 * §3.8). The single caller, {@link MessageProcessor}, runs each call inside a gateway-frame handler on a
 * virtual thread ({@code @RunOnVirtualThread}), where the REST client blocks the virtual thread without
 * pinning the carrier thread.
 *
 * <p>Unlike Telegram (which embeds the token in the URL path), the Discord bot token is a per-deployment
 * secret passed as the {@code Authorization: Bot <token>} <em>header</em> per invocation (a
 * {@link HeaderParam}) — read from {@code channels/discord.json} at runtime, never a compile-time
 * constant. The static base URL {@code quarkus.rest-client."discord-api".url} is a fixed, non-secret
 * value ({@code https://discord.com/api/v10}, set in {@code microprofile-config.properties}); only the
 * token varies, and it travels in the header (so it never lands in a request-URL log line).
 */
@RegisterRestClient(configKey = "discord-api")
public interface DiscordRestClient {

    /**
     * Post a message to a Discord channel ({@code POST /channels/{channelId}/messages}). The response
     * envelope is ignored — a failed send is surfaced by the thrown exception, logged (redacted) by the
     * caller.
     *
     * @param authorization the {@code Bot <token>} authorization header value (the per-deployment secret)
     * @param channelId     the snowflake channel id to post into
     * @param body          the {@code { "content": ... }} request body
     */
    @POST
    @Path("/channels/{channelId}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    void createMessage(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                       @PathParam("channelId") String channelId,
                       CreateMessage body);
}
