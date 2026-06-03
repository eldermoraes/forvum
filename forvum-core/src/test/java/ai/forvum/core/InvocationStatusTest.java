package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link InvocationStatus} mirrors the {@code tool_invocations.status} CHECK constraint (ULTRAPLAN section 4.3.3). */
class InvocationStatusTest {

    @Test
    void dbValuesMatchSchemaTokens() {
        assertEquals("ok", InvocationStatus.OK.dbValue());
        assertEquals("error", InvocationStatus.ERROR.dbValue());
        assertEquals("denied", InvocationStatus.DENIED.dbValue());
    }

    @Test
    void fromDbValueRoundTripsEveryConstant() {
        for (InvocationStatus s : InvocationStatus.values()) {
            assertEquals(s, InvocationStatus.fromDbValue(s.dbValue()));
        }
    }

    @Test
    void fromDbValueRejectsUnknown() {
        assertThrows(IllegalStateException.class, () -> InvocationStatus.fromDbValue("timeout"));
        assertThrows(IllegalStateException.class, () -> InvocationStatus.fromDbValue(null));
    }
}
