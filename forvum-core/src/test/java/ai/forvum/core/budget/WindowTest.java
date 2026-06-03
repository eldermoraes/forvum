package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** {@link Window} permits and their validation (section 4.3.5.2). */
class WindowTest {

    @Test
    void dayWindowRejectsNullZone() {
        assertThrows(IllegalStateException.class, () -> new DayWindow(null));
    }

    @Test
    void dayWindowKeepsZone() {
        assertEquals(ZoneId.of("UTC"), new DayWindow(ZoneId.of("UTC")).tz());
    }

    @Test
    void sessionWindowRejectsBlankIds() {
        assertThrows(IllegalStateException.class, () -> new SessionWindow(null, "a"));
        assertThrows(IllegalStateException.class, () -> new SessionWindow("", "a"));
        assertThrows(IllegalStateException.class, () -> new SessionWindow("s", " "));
    }

    @Test
    void sessionWindowKeepsIds() {
        SessionWindow w = new SessionWindow("sess", "agent");
        assertEquals("sess", w.sessionId());
        assertEquals("agent", w.agentId());
    }

    /** Exhaustive switch over the sealed {@link Window} with no {@code default} — proof of closure. */
    @Test
    void switchOverWindowIsExhaustiveWithoutDefault() {
        Window w = new DayWindow(ZoneId.of("UTC"));
        String kind = switch (w) {
            case DayWindow d -> "day";
            case SessionWindow s -> "session";
        };
        assertEquals("day", kind);
    }
}
