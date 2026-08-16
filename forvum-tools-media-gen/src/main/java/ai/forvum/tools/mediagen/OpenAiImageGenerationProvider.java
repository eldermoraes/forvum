package ai.forvum.tools.mediagen;

import ai.forvum.sdk.AbstractGenerationProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.GenerationRequest;
import ai.forvum.sdk.MediaKind;

import ai.forvum.tools.mediagen.dto.GeneratedImage;
import ai.forvum.tools.mediagen.dto.ImageGenerationRequest;
import ai.forvum.tools.mediagen.dto.ImageGenerationResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.Set;

/**
 * The bundled OpenAI-compatible images backend (#187): a concrete {@link ai.forvum.sdk.GenerationProvider}
 * serving {@link MediaKind#IMAGE} through {@code POST /v1/images/generations} with
 * {@code response_format=b64_json}, over a blocking REST client on the caller's virtual thread (the
 * Qdrant/Telegram REST recipe — no reactive types, no AI library, no engine dependency). It is the SPI's
 * REFERENCE implementor: {@link ForvumExtension} + {@code META-INF/forvum/plugin.json} (provider
 * {@code "type": "generation"}) make it build-time discoverable; a third-party backend (video, music,
 * another image API) follows the same shape.
 *
 * <p><strong>Inert until configured.</strong> With no {@code tools/media-gen.json} (or no {@code baseUrl})
 * {@link #isActive()} is {@code false} and {@link #generate(GenerationRequest)} throws an actionable
 * error naming the file to create — never a network call, so the CI native no-config smoke stays clean.
 */
@ForvumExtension
@ApplicationScoped
public class OpenAiImageGenerationProvider extends AbstractGenerationProvider {

    private static final Logger LOG = Logger.getLogger(OpenAiImageGenerationProvider.class);

    private final MediaGenConfig config;
    private final ImagesApi api;

    @Inject
    public OpenAiImageGenerationProvider(MediaGenConfig config, @RestClient ImagesApi api) {
        this.config = config;
        this.api = api;
    }

    @Override
    public String extensionId() {
        return "media-gen";
    }

    /**
     * Active only when {@code tools/media-gen.json} carries a {@code baseUrl}. An unreadable config is
     * treated as inactive (the Qdrant posture) so a malformed file never selects this backend; the
     * misconfiguration still surfaces actionably when {@link #generate(GenerationRequest)} runs.
     */
    @Override
    public boolean isActive() {
        try {
            return config.read().isReady();
        } catch (RuntimeException e) {
            LOG.debugf("media-gen config unreadable (%s); treating as inactive.", e.getMessage());
            return false;
        }
    }

    @Override
    public Set<MediaKind> supportedKinds() {
        return Set.of(MediaKind.IMAGE);
    }

    @Override
    public GeneratedMedia generate(GenerationRequest request) {
        MediaGenConfig.Spec spec = config.read();
        if (!spec.isReady()) {
            throw new MediaGenException("image.generate is not configured: create "
                    + "$FORVUM_HOME/tools/media-gen.json with \"baseUrl\" (an OpenAI-compatible images "
                    + "endpoint) and optionally \"apiKey\" and \"model\".");
        }
        String baseUrl = spec.baseUrl().orElseThrow();
        String authorization = spec.apiKey().map(key -> "Bearer " + key).orElse("");
        ImageGenerationRequest body = new ImageGenerationRequest(
                spec.model().orElse(null), request.prompt(), 1, "b64_json");

        ImageGenerationResponse response;
        try {
            response = api.generate(baseUrl, authorization, body);
        } catch (MediaGenException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MediaGenException("The image-generation backend call to " + baseUrl
                    + " failed: " + e.getMessage());
        }
        return new GeneratedMedia(MediaKind.IMAGE, decodeFirstImage(baseUrl, response), "png");
    }

    /** Extract + decode the first {@code b64_json} payload, with an actionable error per failure mode. */
    private static byte[] decodeFirstImage(String baseUrl, ImageGenerationResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new MediaGenException("The image-generation backend at " + baseUrl
                    + " returned no image data.");
        }
        GeneratedImage first = response.data().getFirst();
        if (first.b64Json() == null || first.b64Json().isBlank()) {
            if (first.url() != null && !first.url().isBlank()) {
                throw new MediaGenException("The image-generation backend at " + baseUrl
                        + " returned a hosted URL instead of the requested b64_json payload; configure a "
                        + "backend that honors response_format=b64_json.");
            }
            throw new MediaGenException("The image-generation backend at " + baseUrl
                    + " returned an entry with no image payload.");
        }
        try {
            return Base64.getDecoder().decode(first.b64Json());
        } catch (IllegalArgumentException e) {
            throw new MediaGenException("The image-generation backend at " + baseUrl
                    + " returned an invalid base64 payload: " + e.getMessage());
        }
    }
}
