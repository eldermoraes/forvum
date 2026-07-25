package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link MimeTypes}: magic bytes beat the extension; unknown → actionable reject. */
class MimeTypesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 3, 2, 1, 'W', 'E', 'B', 'P'};

    @Test
    void magicBytesAreDetected() {
        assertEquals("image/png", MimeTypes.detect(PNG, "x"));
        assertEquals("image/jpeg", MimeTypes.detect(JPEG, "x"));
        assertEquals("image/gif", MimeTypes.detect(GIF, "x"));
        assertEquals("image/webp", MimeTypes.detect(WEBP, "x"));
        assertEquals("application/pdf", MimeTypes.detect(PDF, "x"));
    }

    @Test
    void magicBytesBeatTheExtension() {
        // A PNG file mislabeled .pdf is classified as PNG from the bytes, not the extension.
        assertEquals("image/png", MimeTypes.detect(PNG, "misleading.pdf"));
    }

    @Test
    void extensionIsTheFallbackWhenBytesAreInconclusive() {
        byte[] plain = {'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o'};
        assertEquals("image/png", MimeTypes.detect(plain, "a.PNG"));
        assertEquals("application/pdf", MimeTypes.detect(plain, "a.pdf"));
        assertEquals("image/jpeg", MimeTypes.detect(plain, "a.jpeg"));
    }

    @Test
    void unknownTypeIsRejectedActionably() {
        byte[] plain = {'n', 'o', 'p', 'e', '!', '!', '!', '!'};
        MultimodalException e = assertThrows(MultimodalException.class,
                () -> MimeTypes.detect(plain, "mystery.bin"));
        assertEquals(true, e.getMessage().contains("mystery.bin"));
    }

    @Test
    void isImageClassifies() {
        assertEquals(true, MimeTypes.isImage("image/png"));
        assertEquals(false, MimeTypes.isImage("application/pdf"));
        assertEquals(false, MimeTypes.isImage(null));
    }

    @Test
    void sniffReturnsNullForNullShortOrPartialMagic() {
        assertEquals(null, MimeTypes.sniff(null));
        assertEquals(null, MimeTypes.sniff(new byte[] {1, 2, 3})); // < 4 bytes
        assertEquals(null, MimeTypes.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'X'})); // PNG magic near-miss
        assertEquals(null, MimeTypes.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00, 0x00})); // JPEG near-miss
        assertEquals(null, MimeTypes.sniff(new byte[] {'G', 'I', 'F', '7'})); // GIF near-miss
        assertEquals(null, MimeTypes.sniff(new byte[] {'%', 'P', 'D', 'X'})); // PDF near-miss
        assertEquals(null, MimeTypes.sniff(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'X', 'X', 'X', 'X'})); // RIFF not WEBP
    }

    @Test
    void extensionFallbackCoversEveryTypeAndNullAndUnknown() {
        byte[] inconclusive = {'z', 'z', 'z', 'z'};
        assertEquals("image/gif", MimeTypes.detect(inconclusive, "a.gif"));
        assertEquals("image/webp", MimeTypes.detect(inconclusive, "a.webp"));
        assertEquals("image/jpeg", MimeTypes.detect(inconclusive, "a.jpg"));
        assertEquals(null, MimeTypes.fromExtension(null));
        assertEquals(null, MimeTypes.fromExtension("noext"));
        assertThrows(MultimodalException.class, () -> MimeTypes.detect(inconclusive, "a.txt"));
    }
}
