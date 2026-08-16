package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

/**
 * Validation contracts of the {@link GenerationProvider} SPI value types (#187):
 * {@link GenerationRequest} and {@link GeneratedMedia} reject the inputs a misbehaving caller could
 * produce (null kind, blank prompt, empty bytes, dotted/blank extension), and the sealed hierarchy
 * admits a third-party implementation through {@link AbstractGenerationProvider}.
 */
class GenerationContractsTest {

    @ParameterizedTest
    @EnumSource(MediaKind.class)
    void requestAcceptsEveryKindWithANonBlankPrompt(MediaKind kind) {
        GenerationRequest request = new GenerationRequest(kind, "a red circle on white");

        assertEquals(kind, request.kind());
        assertEquals("a red circle on white", request.prompt());
    }

    @Test
    void requestRejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationRequest(null, "a prompt"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requestRejectsBlankPrompt(String prompt) {
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationRequest(MediaKind.IMAGE, prompt));
    }

    @Test
    void mediaCarriesKindBytesAndExtension() {
        GeneratedMedia media = new GeneratedMedia(MediaKind.IMAGE, new byte[] {1, 2, 3}, "png");

        assertEquals(MediaKind.IMAGE, media.kind());
        assertEquals(3, media.data().length);
        assertEquals("png", media.fileExtension());
    }

    @Test
    void mediaRejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedMedia(null, new byte[] {1}, "png"));
    }

    @Test
    void mediaRejectsNullOrEmptyBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedMedia(MediaKind.IMAGE, null, "png"));
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedMedia(MediaKind.IMAGE, new byte[0], "png"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".png"})
    void mediaRejectsBlankOrDottedExtension(String extension) {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratedMedia(MediaKind.IMAGE, new byte[] {1}, extension));
    }

    @Test
    void sealedInterfaceAdmitsAThirdPartyImplementationViaTheAbstractBase() {
        GenerationProvider provider = new AbstractGenerationProvider() {
            @Override
            public String extensionId() {
                return "fake-gen";
            }

            @Override
            public Set<MediaKind> supportedKinds() {
                return Set.of(MediaKind.IMAGE);
            }

            @Override
            public GeneratedMedia generate(GenerationRequest request) {
                return new GeneratedMedia(request.kind(), new byte[] {42}, "png");
            }
        };

        assertTrue(provider.isActive(), "the default isActive() is true");
        assertEquals("fake-gen", provider.extensionId());
        assertEquals(Set.of(MediaKind.IMAGE), provider.supportedKinds());
        assertEquals("png",
                provider.generate(new GenerationRequest(MediaKind.IMAGE, "anything")).fileExtension());
    }
}
