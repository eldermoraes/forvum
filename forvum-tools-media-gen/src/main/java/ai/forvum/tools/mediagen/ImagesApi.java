package ai.forvum.tools.mediagen;

import ai.forvum.tools.mediagen.dto.ImageGenerationRequest;
import ai.forvum.tools.mediagen.dto.ImageGenerationResponse;

import io.quarkus.rest.client.reactive.Url;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Blocking REST client for an OpenAI-compatible images endpoint (#187; mirrors {@code QdrantApi} /
 * {@code TelegramBotApi}). It is a plain blocking client whose method returns a typed value directly —
 * NOT a Mutiny {@code Uni}/{@code Multi} return type (reactive where a virtual thread suffices is a
 * PR-reject, CLAUDE.md §3.8). The single caller, {@link OpenAiImageGenerationProvider}, runs the call
 * inside {@code generate} on a virtual thread, where the REST client blocks the virtual thread without
 * pinning the carrier thread.
 *
 * <p>The backend base URL is a per-deployment value read from {@code tools/media-gen.json} at runtime,
 * so it is supplied as a per-invocation {@code @Url} override; the mandatory static
 * {@code quarkus.rest-client."media-gen-api".url} is a placeholder the {@code @Url} replaces. The
 * optional API key is passed on the {@code Authorization} header per call (blank
 * when unset, which a local unsecured backend ignores).
 */
@RegisterRestClient(configKey = "media-gen-api")
public interface ImagesApi {

    /**
     * Image generation: {@code POST /v1/images/generations}. Returns the generated image(s) as
     * base64-encoded bytes ({@code response_format=b64_json}).
     *
     * @param baseUrl       per-invocation backend base URL
     * @param authorization the {@code Authorization} header value (blank when unset)
     * @param request       the generation body (model, prompt, n, response_format)
     */
    @POST
    @Path("/v1/images/generations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    ImageGenerationResponse generate(@Url String baseUrl,
                                     @HeaderParam("Authorization") String authorization,
                                     ImageGenerationRequest request);
}
