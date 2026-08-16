package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.sdk.GenerationProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies the media-generation extension WIRES under Quarkus: ArC discovers the
 * {@link MediaGenToolProvider} tool bean and the {@link OpenAiImageGenerationProvider} as the
 * {@link GenerationProvider} bean (whose {@code @RestClient ImagesApi} injects — the native-relevant CDI
 * + rest-client path). With no {@code tools/media-gen.json}, every surface is INERT: the config gap names
 * the exact file + field, {@code image.generate} fails actionably without a network call, and
 * {@code video.generate} reports the capability gap (the bundled backend is images-only). Boots Quarkus
 * in-JVM; runs under Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class MediaGenWiringIT {

    @Inject
    MediaGenToolProvider toolProvider;

    @Inject
    GenerationProvider generationProvider;   // resolves to the single OpenAiImageGenerationProvider bean

    @Test
    void beansAreDiscoveredWithTheExpectedExtensionIds() {
        assertNotNull(toolProvider);
        assertEquals("media-gen", toolProvider.extensionId());
        assertInstanceOf(OpenAiImageGenerationProvider.class, generationProvider);
        assertEquals("media-gen", generationProvider.extensionId());
    }

    @Test
    void unconfiguredBackendIsFlaggedAsAConfigGap() {
        // The test JVM has no ~/.forvum/tools/media-gen.json, so the bundled backend must report the gap.
        Map<String, String> gaps = toolProvider.configGaps();
        assertTrue(gaps.containsKey("image.generate"), gaps.toString());
        assertTrue(gaps.get("image.generate").contains("media-gen.json"), gaps.toString());
    }

    @Test
    void imageGenerateIsInertWithNoConfig() {
        MediaGenException e = assertThrows(MediaGenException.class,
                () -> toolProvider.invoke("image.generate", Map.of("prompt", "a cat")));
        assertTrue(e.getMessage().contains("tools/media-gen.json"), e.getMessage());
    }

    @Test
    void videoGenerateReportsTheCapabilityGap() {
        MediaGenException e = assertThrows(MediaGenException.class,
                () -> toolProvider.invoke("video.generate", Map.of("prompt", "a cat")));
        assertTrue(e.getMessage().contains("no installed generation backend supports video"),
                e.getMessage());
    }
}
