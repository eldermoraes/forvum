package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.sdk.MediaPayload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@link ImageAnalyzeTool}: single + multi image reach the seam with EXACT bytes; oversize is stat-rejected
 *  before read; escape rejected; missing paths → IAE; a seam failure propagates. */
class ImageAnalyzeToolTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 8};

    @TempDir
    Path ws;

    private FakeMediaAnalysis fake;
    private WorkspaceRoot root;

    @BeforeEach
    void setUp() {
        fake = new FakeMediaAnalysis();
        root = new WorkspaceRoot(ws);
    }

    private MultimodalToolConfig.Spec spec(long maxBytes) {
        return new MultimodalToolConfig.Spec(Optional.of("capture:vision"), maxBytes, 50_000);
    }

    @Test
    void singleImageReachesTheSeamWithExactBytesAndDefaultPromptAndOverride() throws IOException {
        Files.write(ws.resolve("a.png"), PNG);
        String out = ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("a.png"), null);

        assertEquals("analysis-result", out);
        assertEquals(1, fake.lastMedia.size());
        MediaPayload payload = fake.lastMedia.get(0);
        assertEquals("image/png", payload.mimeType());
        assertArrayEquals(PNG, payload.data(), "the EXACT file bytes reach the seam (not a stub)");
        assertEquals("a.png", payload.sourceName());
        assertEquals(ImageAnalyzeTool.DEFAULT_PROMPT, fake.lastPrompt);
        assertEquals("capture:vision", fake.lastOverride, "the config model override is forwarded");
    }

    @Test
    void multipleImagesAllReachTheSeam() throws IOException {
        Files.write(ws.resolve("a.png"), PNG);
        Files.write(ws.resolve("b.jpg"), JPEG);
        ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("a.png", "b.jpg"), "compare these");

        assertEquals(2, fake.lastMedia.size());
        assertEquals("compare these", fake.lastPrompt);
        assertArrayEquals(JPEG, fake.lastMedia.get(1).data());
    }

    @Test
    void oversizeFileIsRejectedBeforeReachingTheSeam() throws IOException {
        Files.write(ws.resolve("big.png"), PNG); // 8 bytes, cap is 4
        assertThrows(MultimodalException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(4), List.of("big.png"), null));
        assertEquals(0, fake.analyzeCalls, "an oversize file never reaches the model");
    }

    @Test
    void aBlankPromptUsesTheDefault() throws IOException {
        Files.write(ws.resolve("a.png"), PNG);
        ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("a.png"), "   ");
        assertEquals(ImageAnalyzeTool.DEFAULT_PROMPT, fake.lastPrompt);
    }

    @Test
    void aMissingFileIsRejected() {
        assertThrows(MultimodalException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("gone.png"), null));
    }

    @Test
    void aBlankPathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MediaLoader.load(root, 1_000_000, "   "));
    }

    @Test
    void aWorkspaceEscapeIsRejected() {
        assertThrows(WorkspaceEscapeException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("../evil.png"), null));
        assertEquals(0, fake.analyzeCalls);
    }

    @Test
    void aNonImageFileIsRejected() throws IOException {
        Files.write(ws.resolve("d.pdf"), new byte[] {'%', 'P', 'D', 'F', '-'});
        assertThrows(MultimodalException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("d.pdf"), null));
    }

    @Test
    void emptyOrNullPathsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), null, null));
    }

    @Test
    void aSeamFailurePropagates() throws IOException {
        Files.write(ws.resolve("a.png"), PNG);
        fake.throwOnAnalyze = true;
        assertThrows(IllegalStateException.class,
                () -> ImageAnalyzeTool.analyze(fake, root, spec(1_000_000), List.of("a.png"), null));
    }
}
