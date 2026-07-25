package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link MediaPayload} canonical-constructor validation + accessor round-trip (CLAUDE.md §11). */
class MediaPayloadTest {

    private static final byte[] BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void accessorsRoundTrip() {
        MediaPayload p = new MediaPayload("image/png", BYTES, "pic.png");
        assertEquals("image/png", p.mimeType());
        assertArrayEquals(BYTES, p.data());
        assertEquals("pic.png", p.sourceName());
    }

    @Test
    void blankMimeTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MediaPayload(null, BYTES, "pic.png"));
        assertThrows(IllegalArgumentException.class, () -> new MediaPayload("  ", BYTES, "pic.png"));
    }

    @Test
    void nullOrEmptyDataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MediaPayload("image/png", null, "pic.png"));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaPayload("image/png", new byte[0], "pic.png"));
    }

    @Test
    void blankSourceNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MediaPayload("image/png", BYTES, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaPayload("image/png", BYTES, " "));
    }
}
