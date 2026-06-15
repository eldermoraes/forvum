package ai.forvum.channel.whatsapp;

import ai.forvum.channel.whatsapp.dto.SendMessageRequest;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for the Meta WhatsApp Business Graph API's send endpoint (the outbound reply path;
 * the inbound path is the {@link WhatsAppWebhook}). A plain BLOCKING client whose method returns the
 * response directly — NOT a Mutiny {@code Uni}/{@code Multi} (reactive where a virtual thread suffices is
 * a PR-reject, CLAUDE.md §3.8). The single caller, {@link MessageProcessor}, runs each send inside the
 * webhook's virtual-thread worker, where the blocking client does not pin the carrier thread.
 *
 * <p>The base URL ({@code https://graph.facebook.com}) is fixed config (microprofile-config, key
 * {@code whatsapp-graph-api}); the per-account {@code apiVersion} and {@code phoneNumberId} ride the path,
 * and the access token travels in the {@code Authorization: Bearer <token>} header per invocation — NEVER
 * in the URL, so a thrown rest-client exception (which echoes the URL) cannot leak it.
 */
@RegisterRestClient(configKey = "whatsapp-graph-api")
public interface GraphApi {

    /**
     * POST a message to {@code /{apiVersion}/{phoneNumberId}/messages}. The returned envelope carries
     * {@code messages[].id} on success or an {@code error} object on failure (HTTP 200 with an error is
     * possible); the caller inspects {@code error} and never logs the user's content. A transport failure
     * is surfaced by a thrown exception, logged (redacted) by the caller.
     *
     * @param apiVersion    the Graph API version segment (e.g. {@code v21.0})
     * @param phoneNumberId the WhatsApp Business phone-number id the send is addressed from
     * @param authorization the {@code Bearer <accessToken>} header value
     * @param body          the text-message send body
     */
    @POST
    @Path("/{apiVersion}/{phoneNumberId}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonNode send(@PathParam("apiVersion") String apiVersion,
                  @PathParam("phoneNumberId") String phoneNumberId,
                  @HeaderParam("Authorization") String authorization,
                  SendMessageRequest body);
}
