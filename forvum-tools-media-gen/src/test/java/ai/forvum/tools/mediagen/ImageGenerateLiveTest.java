package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.GenerationRequest;
import ai.forvum.sdk.MediaKind;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ONE test that needs a real image-generation backend (#187), {@code @Tag("live")} so it is
 * default-off in CI (nightly / manual per repo policy). It drives a single real generation through the
 * injected provider (the real {@code @RestClient} path) and asserts a non-trivial PNG comes back.
 * Configure {@code ~/.forvum/tools/media-gen.json} with a {@code baseUrl} (and {@code apiKey}/{@code
 * model} as the backend requires); the test SKIPS when unconfigured (green-by-skip, the nightly-live
 * posture).
 *
 * <p>Run with {@code ./mvnw -pl forvum-tools-media-gen test -Dgroups=live -DexcludedGroups=}.
 */
@Tag("live")
@QuarkusTest
class ImageGenerateLiveTest {

    @Inject
    OpenAiImageGenerationProvider provider;

    @Test
    void generatesARealImage() {
        assumeTrue(provider.isActive(),
                "set baseUrl (+ apiKey/model) in ~/.forvum/tools/media-gen.json to run this live test");

        GeneratedMedia media = provider.generate(
                new GenerationRequest(MediaKind.IMAGE, "a small red circle on a white background"));

        assertTrue(media.data().length > 100, "a real image is non-trivial, got "
                + media.data().length + " bytes");
        assertTrue("png".equals(media.fileExtension()), media.fileExtension());
    }
}
