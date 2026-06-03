package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** {@link ChannelMessage} inbound wrapper: identifiers non-blank, content/timestamp non-null (section 4.3 backfill). */
class ChannelMessageTest {

    private static final Instant TS = Instant.parse("2026-06-03T12:00:00Z");

    @Test
    void acceptsValid() {
        ChannelMessage m = new ChannelMessage("telegram", "12345", "hi", TS);
        assertEquals("telegram", m.channelId());
        assertEquals("12345", m.nativeUserId());
        assertEquals("hi", m.content());
        assertEquals(TS, m.timestamp());
    }

    @Test
    void allowsEmptyContent() {
        new ChannelMessage("tui", "local", "", TS);
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalStateException.class, () -> new ChannelMessage(null, "u", "c", TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage(" ", "u", "c", TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage(" tui", "u", "c", TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage("tui", null, "c", TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage("tui", " ", "c", TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage("tui", "u", null, TS));
        assertThrows(IllegalStateException.class, () -> new ChannelMessage("tui", "u", "c", null));
    }
}
