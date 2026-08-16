package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.GenerationRequest;
import ai.forvum.sdk.MediaKind;

import ai.forvum.tools.mediagen.dto.GeneratedImage;
import ai.forvum.tools.mediagen.dto.ImageGenerationRequest;
import ai.forvum.tools.mediagen.dto.ImageGenerationResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link OpenAiImageGenerationProvider}'s request/response mapping against a hand-written
 * fake {@link ImagesApi} (the qdrant {@code FakeQdrantApi} pattern — pure, Quarkus-free): the wire request
 * carries the configured model + the prompt + {@code n=1} + {@code b64_json}; the auth header is the
 * {@code Bearer} form of the configured key (blank when unset); every failure mode (unconfigured, no data,
 * URL-instead, invalid base64, backend exception) yields an actionable {@link MediaGenException}.
 */
class OpenAiImageGenerationProviderTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    private static MediaGenConfig config(Path dir, String json) throws IOException {
        Path file = dir.resolve("media-gen.json");
        if (json != null) {
            Files.writeString(file, json);
        }
        return new MediaGenConfig(file);
    }

    @Test
    void supportsImagesOnlyAndCarriesTheExtensionId(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, null), (url, auth, req) -> null);
        assertEquals("media-gen", provider.extensionId());
        assertEquals(Set.of(MediaKind.IMAGE), provider.supportedKinds());
    }

    @Test
    void inactiveAndActionablyUnconfiguredWithoutBaseUrl(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, null), (url, auth, req) -> null);

        assertFalse(provider.isActive());
        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat")));
        assertTrue(e.getMessage().contains("tools/media-gen.json"), e.getMessage());
        assertTrue(e.getMessage().contains("baseUrl"), e.getMessage());
    }

    @Test
    void malformedConfigIsTreatedAsInactive(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{ not json"), (url, auth, req) -> null);
        assertFalse(provider.isActive());
    }

    @Test
    void happyPathMapsRequestAndDecodesTheB64Payload(@TempDir Path dir) throws IOException {
        AtomicReference<String> seenUrl = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<ImageGenerationRequest> seenRequest = new AtomicReference<>();
        ImagesApi fake = (url, auth, req) -> {
            seenUrl.set(url);
            seenAuth.set(auth);
            seenRequest.set(req);
            return new ImageGenerationResponse(List.of(
                    new GeneratedImage(Base64.getEncoder().encodeToString(PNG_BYTES), null)));
        };
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"https://img.example\",\"apiKey\":\"k-1\",\"model\":\"img-1\"}"),
                fake);

        assertTrue(provider.isActive());
        GeneratedMedia media = provider.generate(new GenerationRequest(MediaKind.IMAGE, "a red fox"));

        assertEquals("https://img.example", seenUrl.get());
        assertEquals("Bearer " + "k-1", seenAuth.get());
        assertEquals(new ImageGenerationRequest("img-1", "a red fox", 1, "b64_json"), seenRequest.get());
        assertEquals(MediaKind.IMAGE, media.kind());
        assertEquals("png", media.fileExtension());
        assertArrayEquals(PNG_BYTES, media.data());
    }

    @Test
    void absentApiKeyAndModelSendBlankAuthAndNullModel(@TempDir Path dir) throws IOException {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<ImageGenerationRequest> seenRequest = new AtomicReference<>();
        ImagesApi fake = (url, auth, req) -> {
            seenAuth.set(auth);
            seenRequest.set(req);
            return new ImageGenerationResponse(List.of(
                    new GeneratedImage(Base64.getEncoder().encodeToString(PNG_BYTES), null)));
        };
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"http://localhost:1234\"}"), fake);

        provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat"));

        assertEquals("", seenAuth.get(), "no apiKey → blank Authorization value");
        assertEquals(new ImageGenerationRequest(null, "a cat", 1, "b64_json"), seenRequest.get());
    }

    @Test
    void emptyDataFailsActionably(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"https://img.example\"}"),
                (url, auth, req) -> new ImageGenerationResponse(null));

        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat")));
        assertTrue(e.getMessage().contains("no image data"), e.getMessage());
    }

    @Test
    void urlInsteadOfB64FailsActionably(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"https://img.example\"}"),
                (url, auth, req) -> new ImageGenerationResponse(List.of(
                        new GeneratedImage(null, "https://cdn.example/img.png"))));

        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat")));
        assertTrue(e.getMessage().contains("URL instead"), e.getMessage());
    }

    @Test
    void invalidBase64FailsActionably(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"https://img.example\"}"),
                (url, auth, req) -> new ImageGenerationResponse(List.of(
                        new GeneratedImage("!!!not-base64!!!", null))));

        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat")));
        assertTrue(e.getMessage().contains("base64"), e.getMessage());
    }

    @Test
    void backendFailureIsWrappedActionably(@TempDir Path dir) throws IOException {
        OpenAiImageGenerationProvider provider = new OpenAiImageGenerationProvider(
                config(dir, "{\"baseUrl\":\"https://img.example\"}"),
                (url, auth, req) -> {
                    throw new IllegalStateException("connection refused");
                });

        MediaGenException e = assertThrows(MediaGenException.class,
                () -> provider.generate(new GenerationRequest(MediaKind.IMAGE, "a cat")));
        assertTrue(e.getMessage().contains("connection refused"), e.getMessage());
        assertTrue(e.getMessage().contains("https://img.example"), e.getMessage());
    }
}
