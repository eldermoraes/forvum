package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;

import ai.forvum.sdk.AbstractGenerationProvider;
import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.GenerationProvider;
import ai.forvum.sdk.GenerationRequest;
import ai.forvum.sdk.MediaKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unit tests for {@link MediaGenToolProvider}: the three constant specs all carry
 * {@link PermissionScope#MEDIA_GENERATE} with no boot IO, argument validation is actionable, and backend
 * selection (pure {@link MediaGenToolProvider#selectProvider}) prefers an ACTIVE candidate, falls back to
 * an inactive one (so its own "not configured" error reaches the model), and reports a capability gap
 * when no installed backend supports the kind.
 */
class MediaGenToolProviderTest {

    /** A fake backend with a fixed kind set + active flag (pure, Quarkus-free). */
    private static final class FakeBackend extends AbstractGenerationProvider {
        private final Set<MediaKind> kinds;
        private final boolean active;

        FakeBackend(Set<MediaKind> kinds, boolean active) {
            this.kinds = kinds;
            this.active = active;
        }

        @Override
        public String extensionId() {
            return "fake";
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public Set<MediaKind> supportedKinds() {
            return kinds;
        }

        @Override
        public GeneratedMedia generate(GenerationRequest request) {
            return new GeneratedMedia(request.kind(), new byte[] {1}, "bin");
        }
    }

    @Test
    void contributesThreeToolsAllGatedByMediaGenerate() {
        MediaGenToolProvider provider = new MediaGenToolProvider();
        assertEquals("media-gen", provider.extensionId());
        assertEquals(List.of("image.generate", "video.generate", "music.generate"),
                provider.tools().stream().map(spec -> spec.name()).toList());
        provider.tools().forEach(spec ->
                assertEquals(PermissionScope.MEDIA_GENERATE, spec.requiredScope(), spec.name()));
        provider.tools().forEach(spec ->
                assertTrue(spec.parametersJsonSchema().contains("\"prompt\""), spec.name()));
    }

    @Test
    void unknownToolNameIsAProgrammingError() {
        MediaGenToolProvider provider = new MediaGenToolProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("fs.read", Map.of()));
    }

    @Test
    void blankPromptFailsActionably() {
        MediaGenToolProvider provider = new MediaGenToolProvider();
        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.invoke("image.generate", Map.of("prompt", "  ")));
        assertTrue(e.getMessage().contains("'prompt'"), e.getMessage());
    }

    @Test
    void selectionPrefersAnActiveCandidate() {
        GenerationProvider inactive = new FakeBackend(Set.of(MediaKind.IMAGE), false);
        GenerationProvider active = new FakeBackend(Set.of(MediaKind.IMAGE), true);

        assertSame(active, MediaGenToolProvider.selectProvider(
                "image.generate", MediaKind.IMAGE, List.of(inactive, active)));
    }

    @Test
    void selectionFallsBackToAnInactiveCandidate() {
        GenerationProvider inactive = new FakeBackend(Set.of(MediaKind.IMAGE), false);

        assertSame(inactive, MediaGenToolProvider.selectProvider(
                "image.generate", MediaKind.IMAGE, List.of(inactive)));
    }

    @Test
    void unsupportedKindIsACapabilityGap() {
        GenerationProvider imagesOnly = new FakeBackend(Set.of(MediaKind.IMAGE), true);

        MediaGenException e = assertThrows(MediaGenException.class,
                () -> MediaGenToolProvider.selectProvider(
                        "video.generate", MediaKind.VIDEO, List.of(imagesOnly)));
        assertTrue(e.getMessage().contains("video"), e.getMessage());
        assertTrue(e.getMessage().contains("GenerationProvider"), e.getMessage());
    }

    @Test
    void invokeGeneratesAndWritesUnderTheWorkspaceOutbox(@TempDir Path workspace) {
        MediaGenToolProvider provider = new MediaGenToolProvider();
        provider.generationProviders = null; // never touched: selection uses the iterable seam below
        provider.configuredWorkspaceRoot = Optional.of(workspace.toString());
        // Drive the same steps invoke composes, against the pure seams (the CDI path is covered by the IT).
        GenerationProvider backend = MediaGenToolProvider.selectProvider(
                "music.generate", MediaKind.MUSIC,
                List.of(new FakeBackend(Set.of(MediaKind.MUSIC), true)));
        String result = MediaOutbox.write(provider.workspaceRoot(),
                backend.generate(new GenerationRequest(MediaKind.MUSIC, "calm piano")));

        assertTrue(result.contains("media/music-"), result);
    }

    @Test
    void workspaceRootFallsBackToTheSharedDefault() {
        MediaGenToolProvider provider = new MediaGenToolProvider();
        provider.configuredWorkspaceRoot = Optional.empty();
        assertEquals(Path.of(System.getProperty("user.home"), ".forvum", "workspace"),
                provider.workspaceRoot());
    }
}
