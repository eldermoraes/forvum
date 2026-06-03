package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link EventType} mirrors the {@code episodic_memory.event_type} CHECK constraint (ULTRAPLAN section 4.3.3). */
class EventTypeTest {

    @Test
    void dbValuesMatchSchemaTokens() {
        assertEquals("observation", EventType.OBSERVATION.dbValue());
        assertEquals("decision", EventType.DECISION.dbValue());
        assertEquals("reflection", EventType.REFLECTION.dbValue());
    }

    @Test
    void fromDbValueRoundTripsEveryConstant() {
        for (EventType e : EventType.values()) {
            assertEquals(e, EventType.fromDbValue(e.dbValue()));
        }
    }

    @Test
    void fromDbValueRejectsUnknown() {
        assertThrows(IllegalStateException.class, () -> EventType.fromDbValue("note"));
        assertThrows(IllegalStateException.class, () -> EventType.fromDbValue(null));
    }
}
