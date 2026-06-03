package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link Role} mirrors the {@code messages.role} CHECK constraint (ULTRAPLAN section 4.3.3). */
class RoleTest {

    @Test
    void dbValuesMatchSchemaTokens() {
        assertEquals("user", Role.USER.dbValue());
        assertEquals("assistant", Role.ASSISTANT.dbValue());
        assertEquals("system", Role.SYSTEM.dbValue());
        assertEquals("tool", Role.TOOL.dbValue());
    }

    @Test
    void fromDbValueRoundTripsEveryConstant() {
        for (Role r : Role.values()) {
            assertEquals(r, Role.fromDbValue(r.dbValue()));
        }
    }

    @Test
    void fromDbValueRejectsUnknown() {
        assertThrows(IllegalStateException.class, () -> Role.fromDbValue("admin"));
        assertThrows(IllegalStateException.class, () -> Role.fromDbValue("USER"));
        assertThrows(IllegalStateException.class, () -> Role.fromDbValue(null));
    }
}
