package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.MediaKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link MediaOutbox}: the generated asset lands under {@code <workspace>/media/} with a
 * collision-free {@code <kind>-...<ext>} name, the bytes round-trip exactly, no temp file survives, and
 * the returned message carries the workspace-relative path (the {@code TtsSynthesizer} write contract).
 */
class MediaOutboxTest {

    @Test
    void writesBytesUnderTheMediaOutbox(@TempDir Path workspace) throws IOException {
        byte[] bytes = {1, 2, 3, 4, 5};

        String message = MediaOutbox.write(workspace, new GeneratedMedia(MediaKind.IMAGE, bytes, "png"));

        assertTrue(message.contains("wrote 5 bytes to media/image-"), message);
        try (var files = Files.list(workspace.resolve("media"))) {
            Path out = files.findFirst().orElseThrow();
            String name = out.getFileName().toString();
            assertTrue(name.startsWith("image-") && name.endsWith(".png"), name);
            assertArrayEquals(bytes, Files.readAllBytes(out));
        }
    }

    @Test
    void leavesNoTempFileBehind(@TempDir Path workspace) throws IOException {
        MediaOutbox.write(workspace, new GeneratedMedia(MediaKind.MUSIC, new byte[] {9}, "wav"));

        try (var files = Files.list(workspace.resolve("media"))) {
            assertEquals(1, files.count(), "exactly the moved output file remains");
        }
    }

    @Test
    void kindPrefixesTheGeneratedName(@TempDir Path workspace) throws IOException {
        MediaOutbox.write(workspace, new GeneratedMedia(MediaKind.VIDEO, new byte[] {7}, "mp4"));

        try (var files = Files.list(workspace.resolve("media"))) {
            String name = files.findFirst().orElseThrow().getFileName().toString();
            assertTrue(name.startsWith("video-") && name.endsWith(".mp4"), name);
        }
    }
}
